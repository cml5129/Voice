package voice.core.historyupload

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookMetadata
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.server.VoiceServerConfig
import voice.core.server.VoiceServerSettings
import voice.core.server.VoiceServerStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryUploaderTest {

  private val chapters = listOf(chapter("one", duration = 60_000), chapter("two", duration = 120_000))
  private val book = book(chapters)
  private val history = MemoryHistoryRepo()
  private val store = MemoryDataStore(HistoryUploadSettings())
  private val serverStore = MemoryDataStore(VoiceServerSettings(url = "https://example.com", key = "key"))
  private val api = RecordingApi()
  private val uploader = HistoryUploader(
    store = HistoryUploadStore(store),
    serverConfig = VoiceServerConfig(VoiceServerStore(serverStore)),
    historyRepo = history,
    bookRepository = MemoryBookRepository(book),
    api = api,
    clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC),
  )

  @Test
  fun `nothing goes up without a server`() = runTest {
    serverStore.updateData { VoiceServerSettings() }
    history.add(event(ListeningEvent.Type.Play))

    assertTrue(uploader.upload())
    assertEquals(emptyList(), api.batches)
  }

  @Test
  fun `nothing goes up while the server is switched off`() = runTest {
    serverStore.updateData { it.copy(enabled = false) }
    history.add(event(ListeningEvent.Type.Play))

    assertTrue(uploader.upload())
    assertEquals(emptyList(), api.batches)

    serverStore.updateData { it.copy(enabled = true) }
    assertTrue(uploader.upload())
    assertEquals(1, api.batches.size)
  }

  @Test
  fun `the key from settings goes along`() = runTest {
    history.add(event(ListeningEvent.Type.Play))

    assertTrue(uploader.upload())

    assertEquals(listOf("https://example.com"), api.servers)
    assertEquals(listOf("key"), api.keys)
  }

  @Test
  fun `events go up once, with the book and where in it they happened`() = runTest {
    history.add(event(ListeningEvent.Type.Play, time = 1_000))
    history.add(event(ListeningEvent.Type.Seek, time = 2_000, toChapterId = chapters[1].id, toTime = 5_000))

    assertTrue(uploader.upload())
    assertTrue(uploader.upload())

    assertEquals(1, api.batches.size)
    val (play, seek) = api.batches.single().events
    assertEquals("Play", play.type)
    assertEquals(book.content.name, play.bookName)
    assertEquals("one", play.chapterName)
    assertEquals(1_000L, play.bookTime)
    assertEquals("two", seek.toChapterName)
    assertEquals(65_000L, seek.toBookTime)
    val settings = store.data.first()
    assertEquals(2L, settings.uploadedUpToId)
    assertEquals(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), settings.lastUploadAtMillis)
  }

  @Test
  fun `the device keeps its id`() = runTest {
    history.add(event(ListeningEvent.Type.Play))
    assertTrue(uploader.upload())
    history.add(event(ListeningEvent.Type.Pause))
    assertTrue(uploader.upload())

    assertEquals(2, api.batches.size)
    assertEquals(1, api.batches.map { it.device }.toSet().size)
  }

  @Test
  fun `a long history goes up in batches`() = runTest {
    repeat(HistoryUploader.BATCH_SIZE + 1) { history.add(event(ListeningEvent.Type.Play)) }

    assertTrue(uploader.upload())

    assertEquals(listOf(HistoryUploader.BATCH_SIZE, 1), api.batches.map { it.events.size })
  }

  @Test
  fun `a failed upload is tried again later`() = runTest {
    history.add(event(ListeningEvent.Type.Play))
    api.error = "The server answered 502"

    assertFalse(uploader.upload())
    assertEquals("The server answered 502", store.data.first().lastError)
    assertEquals(0L, store.data.first().uploadedUpToId)

    api.error = null
    assertTrue(uploader.upload())
    assertNull(store.data.first().lastError)
    assertEquals(listOf(1L), api.batches.last().events.map { it.id })
  }

  @Test
  fun `a new server gets the whole history`() = runTest {
    history.add(event(ListeningEvent.Type.Play))
    assertTrue(uploader.upload())

    serverStore.updateData { it.withServer("https://other.example.com/", "other") }
    assertTrue(uploader.upload())

    assertEquals(listOf("https://example.com", "https://other.example.com"), api.servers)
    assertEquals(api.batches[0].events, api.batches[1].events)
  }

  private fun event(
    type: ListeningEvent.Type,
    time: Long = 0,
    toChapterId: ChapterId? = null,
    toTime: Long? = null,
  ) = ListeningEvent(
    bookId = book.id,
    type = type,
    source = ListeningEvent.Source.App,
    atMillis = 0,
    chapterId = chapters[0].id,
    time = time,
    toChapterId = toChapterId,
    toTime = toTime,
  )
}

private class RecordingApi : HistoryUploadApi {

  val batches = mutableListOf<UploadBatch>()
  val servers = mutableListOf<String>()
  val keys = mutableListOf<String>()
  var error: String? = null

  override suspend fun send(
    serverUrl: String,
    key: String,
    batch: UploadBatch,
  ): String? {
    error?.let { return it }
    batches += batch
    servers += serverUrl
    keys += key
    return null
  }
}

private class MemoryHistoryRepo : ListeningHistoryRepo {

  private val events = MutableStateFlow(emptyList<ListeningEvent>())

  override fun events(bookId: BookId): Flow<List<ListeningEvent>> {
    return events.map { events -> events.filter { it.bookId == bookId }.reversed() }
  }

  override suspend fun eventsAfter(
    id: Long,
    limit: Int,
  ): List<ListeningEvent> = events.value.filter { it.id > id }.take(limit)

  override fun newestId(): Flow<Long?> = events.map { events -> events.lastOrNull()?.id }

  override suspend fun add(event: ListeningEvent) {
    events.update { it + event.copy(id = it.size + 1L) }
  }

  override suspend fun moveToBook(
    from: List<BookId>,
    to: BookId,
    chapters: Map<ChapterId, ChapterId>,
  ) {}
}

private class MemoryBookRepository(private val book: Book) : BookRepository {
  override fun flow(): Flow<List<Book>> = MutableStateFlow(listOf(book))
  override suspend fun all(): List<Book> = listOf(book)
  override fun flow(id: BookId): Flow<Book?> = MutableStateFlow(book.takeIf { it.id == id })
  override suspend fun get(id: BookId): Book? = book.takeIf { it.id == id }
  override suspend fun updateBook(
    id: BookId,
    update: (BookContent) -> BookContent,
  ) {}
}

private fun book(chapters: List<Chapter>): Book {
  return Book(
    content = BookContent(
      positionInChapter = 0,
      playbackSpeed = 1F,
      addedAt = Instant.EPOCH,
      chapters = chapters.map { it.id },
      currentChapter = chapters.first().id,
      isActive = true,
      lastPlayedAt = Instant.EPOCH,
      skipSilence = false,
      id = BookId("book"),
      gain = 0F,
      metadata = BookMetadata(
        name = "The Waste Lands",
        author = "Stephen King",
        album = null,
        title = null,
        genre = null,
        narrator = null,
        series = null,
        part = null,
        cover = null,
        fileLastModified = null,
      ),
      customName = null,
      customCover = null,
    ),
    chapters = chapters,
  )
}

private fun chapter(
  name: String,
  duration: Long,
) = Chapter(
  id = ChapterId(name),
  name = name,
  duration = duration,
  fileLastModified = Instant.EPOCH,
  markData = emptyList(),
  fileSize = 0,
)
