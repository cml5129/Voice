package voice.core.transcript

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.ChapterId
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import voice.core.server.VoiceServer
import voice.core.server.VoiceServerConfig
import java.time.Clock
import kotlin.time.Duration.Companion.days

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
    val books = bookRepository.all()
    val chapters = books.flatMap { book -> book.chapters.map { it.id } }.distinct()
    for (chapterId in chapters) {
      val transcript = audioFileName(chapterId)?.let { byKey[transcriptMatchKey(it)] } ?: continue
      val settings = store.data.first()
      val version = transcript.updatedAt ?: transcript.bytes.toString()
      val file = files.transcriptFile(chapterId)
      if (settings.downloaded[chapterId.value] != version || !file.isFile) {
        when (val result = api.download(server, transcript.json, file)) {
          is ServerResult.Success -> {
            repository.invalidate(chapterId)
            store.updateData { it.copy(downloaded = it.downloaded + (chapterId.value to version)) }
          }
          is ServerResult.Failure -> return failed(result.reason)
        }
      }
      val summaries = transcript.summaries
      val summariesVersion = transcript.summariesUpdatedAt ?: ""
      val summariesFile = files.summariesFile(chapterId)
      if (summaries != null && (settings.downloadedSummaries[chapterId.value] != summariesVersion || !summariesFile.isFile)) {
        when (val result = api.download(server, summaries, summariesFile)) {
          is ServerResult.Success -> {
            store.updateData { it.copy(downloadedSummaries = it.downloadedSummaries + (chapterId.value to summariesVersion)) }
          }
          is ServerResult.Failure -> return failed(result.reason)
        }
      }
      // the server may have changed while this file was on its way
      if (serverConfig.current() != server) return true
    }
    requestMissing(server, books.filter { it.chapters.size == 1 }.map { it.chapters.single().id }, byKey.keys)
    store.updateData { it.copy(lastCheckAtMillis = clock.millis(), lastError = null) }
    return true
  }

  /**
   * Asks the server to transcribe the books on this phone it has no transcript of, at most once a week each. The
   * server only transcribes whole-book files, so books split into many files aren't asked for.
   */
  private suspend fun requestMissing(
    server: VoiceServer,
    chapters: List<ChapterId>,
    transcribed: Set<String>,
  ) {
    val now = clock.millis()
    for (chapterId in chapters) {
      val name = audioFileName(chapterId)?.substringBeforeLast('.') ?: continue
      if (transcriptMatchKey(name) in transcribed) continue
      val last = store.data.first().requested[chapterId.value]
      if (last != null && now - last < REQUEST_AGAIN_AFTER_MS) continue
      when (val result = api.request(server, name)) {
        is ServerResult.Success -> store.updateData { it.copy(requested = it.requested + (chapterId.value to now)) }
        // not worth failing the downloads over, the next check asks again
        is ServerResult.Failure -> {
          Logger.w("Transcript request for $name failed: ${result.reason}")
          return
        }
      }
    }
  }

  private suspend fun failed(reason: String): Boolean {
    Logger.w("Transcript download failed: $reason")
    store.updateData { it.copy(lastError = reason) }
    return false
  }
}

private val REQUEST_AGAIN_AFTER_MS = 7.days.inWholeMilliseconds
