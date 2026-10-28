package voice.core.historyupload

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Book
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent
import voice.core.data.markForPosition
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.logging.api.Logger
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

/**
 * Sends the listening history that the server doesn't have yet, in batches, oldest first.
 */
@SingleIn(AppScope::class)
@Inject
public class HistoryUploader internal constructor(
  private val store: HistoryUploadStore,
  private val historyRepo: ListeningHistoryRepo,
  private val bookRepository: BookRepository,
  private val api: HistoryUploadApi,
  private val clock: Clock,
) {

  private val mutex = Mutex()

  /**
   * False when something is still left to send because the server couldn't be reached or refused it.
   */
  public suspend fun upload(): Boolean = mutex.withLock { uploadLocked() }

  private suspend fun uploadLocked(): Boolean {
    val settings = store.data.first()
    if (!settings.enabled) return true
    val device = deviceId(settings)
    var events = historyRepo.eventsAfter(settings.uploadedUpToId, BATCH_SIZE)
    while (events.isNotEmpty()) {
      val books = events.map { it.bookId }.distinct().associateWith { bookRepository.get(it) }
      val batch = UploadBatch(
        device = device,
        zone = ZoneId.systemDefault().id,
        events = events.map { it.toUpload(books[it.bookId]) },
      )
      val error = api.send(settings.serverUrl, settings.key, batch)
      if (error != null) {
        Logger.w("Listening history upload failed: $error")
        store.updateData { it.copy(lastError = error) }
        return false
      }
      val uploadedUpTo = events.last().id
      store.updateData { current ->
        // the server may have changed while this batch was on its way
        if (current.serverUrl != settings.serverUrl) {
          current
        } else {
          current.copy(uploadedUpToId = uploadedUpTo, lastUploadAtMillis = clock.millis(), lastError = null)
        }
      }
      if (events.size < BATCH_SIZE) break
      events = historyRepo.eventsAfter(uploadedUpTo, BATCH_SIZE)
    }
    return true
  }

  private suspend fun deviceId(settings: HistoryUploadSettings): String {
    settings.deviceId?.let { return it }
    val id = UUID.randomUUID().toString()
    return store.updateData { it.copy(deviceId = it.deviceId ?: id) }.deviceId!!
  }

  internal companion object {
    /** Keeps a request well under the server's limit of 300 events and its 100 kB body limit. */
    const val BATCH_SIZE = 200
  }
}

internal fun ListeningEvent.toUpload(book: Book?): UploadEvent {
  return UploadEvent(
    id = id,
    bookId = bookId.value,
    bookName = book?.content?.name,
    author = book?.content?.metadata?.author,
    type = type.name,
    source = source.name,
    at = atMillis,
    chapterId = chapterId.value,
    chapterName = book?.markName(chapterId, time),
    time = time,
    bookTime = book?.bookTime(chapterId, time),
    toChapterId = toChapterId?.value,
    toChapterName = toChapterId?.let { chapter -> toTime?.let { book?.markName(chapter, it) } },
    toTime = toTime,
    toBookTime = toChapterId?.let { chapter -> toTime?.let { book?.bookTime(chapter, it) } },
  )
}

private fun Book.markName(
  chapterId: ChapterId,
  time: Long,
): String? {
  val chapter = chapters.find { it.id == chapterId } ?: return null
  return chapter.markForPosition(time).name ?: chapter.name
}

private fun Book.bookTime(
  chapterId: ChapterId,
  time: Long,
): Long? {
  val index = chapters.indexOfFirst { it.id == chapterId }
  if (index == -1) return null
  return chapters.take(index).sumOf { it.duration } + time
}
