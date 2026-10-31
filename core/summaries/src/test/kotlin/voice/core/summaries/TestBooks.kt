package voice.core.summaries

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookMetadata
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent
import voice.core.data.MarkData
import java.time.Instant

internal const val MINUTE = 60_000L

internal val fileA = ChapterId("file:///storage/Books/a.m4b")
internal val fileB = ChapterId("file:///storage/Books/b.m4b")

internal fun chapter(
  id: ChapterId,
  duration: Long,
  marks: List<MarkData> = emptyList(),
) = Chapter(
  id = id,
  name = id.value,
  duration = duration,
  fileLastModified = Instant.EPOCH,
  markData = marks,
  fileSize = 0,
)

internal fun book(
  chapters: List<Chapter>,
  currentChapter: ChapterId = chapters.first().id,
  positionInChapter: Long = 0,
): Book {
  return Book(
    content = BookContent(
      positionInChapter = positionInChapter,
      playbackSpeed = 1F,
      addedAt = Instant.EPOCH,
      chapters = chapters.map { it.id },
      currentChapter = currentChapter,
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

internal fun event(
  id: Long,
  type: ListeningEvent.Type,
  atMinute: Long,
  chapterId: ChapterId,
  time: Long,
) = ListeningEvent(
  bookId = BookId("book"),
  type = type,
  source = ListeningEvent.Source.App,
  atMillis = atMinute * MINUTE,
  chapterId = chapterId,
  time = time,
  id = id,
)

internal class MemoryDataStore<T>(initial: T) : DataStore<T> {

  private val value = MutableStateFlow(initial)

  override val data: Flow<T> get() = value

  override suspend fun updateData(transform: suspend (t: T) -> T): T {
    return value.updateAndGet { transform(it) }
  }
}
