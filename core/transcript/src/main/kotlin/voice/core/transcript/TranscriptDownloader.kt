package voice.core.transcript

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import voice.core.server.VoiceServerConfig
import java.time.Clock

/**
 * Downloads the transcripts the server has for the books in the library, and again when the server has a newer one.
 * The server transcribes one audio file at a time, so a transcript belongs to a chapter file, matched by file name.
 */
@SingleIn(AppScope::class)
@Inject
public class TranscriptDownloader(
  private val store: TranscriptDownloadStore,
  private val serverConfig: VoiceServerConfig,
  private val bookRepository: BookRepository,
  private val api: ServerTranscriptsApi,
  private val files: TranscriptFiles,
  private val repository: TranscriptRepository,
  private val clock: Clock,
) {

  private val mutex = Mutex()

  /**
   * False when the server couldn't be reached or refused, so it's worth trying again soon.
   */
  public suspend fun download(): Boolean = mutex.withLock { downloadLocked() }

  private suspend fun downloadLocked(): Boolean {
    val server = serverConfig.current() ?: return true
    val listing = when (val result = api.list(server)) {
      is ServerResult.Success -> result.value
      is ServerResult.Failure -> return failed(result.reason)
    }
    val byKey = listing.associateBy { transcriptMatchKey(it.name) }
    val chapters = bookRepository.all().flatMap { book -> book.chapters.map { it.id } }.distinct()
    val downloaded = store.data.first().downloaded
    for (chapterId in chapters) {
      val transcript = audioFileName(chapterId)?.let { byKey[transcriptMatchKey(it)] } ?: continue
      val version = transcript.updatedAt ?: transcript.bytes.toString()
      val file = files.transcriptFile(chapterId)
      if (downloaded[chapterId.value] == version && file.isFile) continue
      when (val result = api.download(server, transcript.json, file)) {
        is ServerResult.Success -> {
          repository.invalidate(chapterId)
          store.updateData { it.copy(downloaded = it.downloaded + (chapterId.value to version)) }
        }
        is ServerResult.Failure -> return failed(result.reason)
      }
      // the server may have changed while this file was on its way
      if (serverConfig.current() != server) return true
    }
    store.updateData { it.copy(lastCheckAtMillis = clock.millis(), lastError = null) }
    return true
  }

  private suspend fun failed(reason: String): Boolean {
    Logger.w("Transcript download failed: $reason")
    store.updateData { it.copy(lastError = reason) }
    return false
  }
}
