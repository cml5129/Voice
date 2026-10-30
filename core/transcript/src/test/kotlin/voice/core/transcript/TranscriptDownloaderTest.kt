package voice.core.transcript

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookMetadata
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookRepository
import voice.core.server.VoiceServer
import voice.core.server.VoiceServerConfig
import voice.core.server.VoiceServerSettings
import voice.core.server.VoiceServerStore
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptDownloaderTest {

  private val wasteLands = ChapterId("file:///storage/Books/The%20Waste%20Lands%20%5BB002V0QK4C%5D.m4b")
  private val untranscribed = ChapterId("file:///storage/Books/Christine%20%5BB00AAA%5D.m4b")
  private val dir: File = Files.createTempDirectory("transcripts").toFile()
  private val files = TranscriptFiles(dir)
  private val store = MemoryDataStore(TranscriptDownloadSettings())
  private val serverStore = MemoryDataStore(VoiceServerSettings(url = "https://example.com", key = "key"))
  private val api = FakeApi()
  private val repository = TranscriptRepository(files)
  private val downloader = TranscriptDownloader(
    store = TranscriptDownloadStore(store),
    serverConfig = VoiceServerConfig(VoiceServerStore(serverStore)),
    bookRepository = MemoryBookRepository(book(listOf(chapter(wasteLands), chapter(untranscribed)))),
    api = api,
    files = files,
    repository = repository,
    clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC),
  )

  @Test
  fun `nothing is fetched without a server`() = runTest {
    serverStore.updateData { it.copy(enabled = false) }

    assertTrue(downloader.download())
    assertEquals(0, api.lists)
  }

  @Test
  fun `the transcript of a library file is downloaded and readable`() = runTest {
    api.listing = listOf(transcript("The Waste Lands [B002V0QK4C]"), transcript("Some Other Book [B0XX]"))

    assertTrue(downloader.download())

    assertEquals(listOf("The Waste Lands [B002V0QK4C].json"), api.downloads)
    assertEquals("Ha! said the gunslinger.", repository.cues(wasteLands)?.single()?.text)
    assertNull(repository.cues(untranscribed))
    val settings = store.data.first()
    assertEquals(setOf(wasteLands.value), settings.downloaded.keys)
    assertEquals(Instant.parse("2026-10-30T12:00:00Z").toEpochMilli(), settings.lastCheckAtMillis)
  }

  @Test
  fun `an unchanged transcript is not downloaded again, a newer one is`() = runTest {
    api.listing = listOf(transcript("The Waste Lands [B002V0QK4C]"))
    downloader.download()
    downloader.download()
    assertEquals(1, api.downloads.size)

    api.text = "Newer."
    api.listing = listOf(transcript("The Waste Lands [B002V0QK4C]", updatedAt = "2026-10-30T09:00:00.000Z"))
    downloader.download()

    assertEquals(2, api.downloads.size)
    assertEquals("Newer.", repository.cues(wasteLands)?.single()?.text)
  }

  @Test
  fun `a failure is kept until a check works`() = runTest {
    api.failure = "The server didn't accept the key"

    assertFalse(downloader.download())
    assertEquals("The server didn't accept the key", store.data.first().lastError)

    api.failure = null
    assertTrue(downloader.download())
    assertNull(store.data.first().lastError)
  }

  private fun transcript(
    name: String,
    updatedAt: String = "2026-10-29T09:00:00.000Z",
  ) = ServerTranscript(name = name, json = "$name.json", lrc = "$name.lrc", bytes = 100, updatedAt = updatedAt)

  private inner class FakeApi : ServerTranscriptsApi {
    var listing = emptyList<ServerTranscript>()
    var failure: String? = null
    var text = "Ha! said the gunslinger."
    var lists = 0
    val downloads = mutableListOf<String>()

    override suspend fun list(server: VoiceServer): ServerResult<List<ServerTranscript>> {
      lists++
      failure?.let { return ServerResult.Failure(it) }
      return ServerResult.Success(listing)
    }

    override suspend fun download(
      server: VoiceServer,
      file: String,
      to: File,
    ): ServerResult<Unit> {
      failure?.let { return ServerResult.Failure(it) }
      downloads += file
      to.parentFile?.mkdirs()
      to.writeText("""[{"timestamp_start":0,"timestamp_end":1000,"text":"$text"}]""")
      return ServerResult.Success(Unit)
    }
  }
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

private fun chapter(id: ChapterId) = Chapter(
  id = id,
  name = id.value,
  duration = 60_000,
  fileLastModified = Instant.EPOCH,
  markData = emptyList(),
  fileSize = 0,
)
