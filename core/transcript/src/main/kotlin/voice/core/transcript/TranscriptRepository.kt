package voice.core.transcript

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import voice.core.data.Book
import voice.core.data.ChapterId
import voice.core.logging.api.Logger

/**
 * The transcripts [TranscriptDownloader] fetched from the listener's server, read per audio file. The cues of the
 * file read last are kept in memory, as a long book's transcript takes a moment to parse.
 */
@SingleIn(AppScope::class)
@Inject
public class TranscriptRepository(private val files: TranscriptFiles) {

  private val lock = Mutex()
  private var cached: Pair<ChapterId, List<TranscriptCue>>? = null

  /** Every cue of the audio file [chapterId], or null without a transcript. */
  public suspend fun cues(chapterId: ChapterId): List<TranscriptCue>? = lock.withLock {
    cached?.takeIf { it.first == chapterId }?.let { return@withLock it.second }
    load(chapterId)?.also { cached = chapterId to it }
  }

  public fun hasTranscript(chapterId: ChapterId): Boolean = files.transcriptFile(chapterId).isFile

  /** Forgets the cues of [chapterId], so a transcript that was just downloaded is read on the next lookup. */
  public suspend fun invalidate(chapterId: ChapterId) {
    lock.withLock { if (cached?.first == chapterId) cached = null }
  }

  /** What the listener has heard of the chapter playing now. */
  public suspend fun chapterSoFar(book: Book): String? {
    val section = book.currentSection()
    return cues(book.currentChapter.id)
      ?.textBetween(section.startMs, book.content.positionInChapter)
      ?.takeUnless { it.isBlank() }
  }

  /** The whole text of [section]; only for sections the listener has already finished. */
  public suspend fun sectionText(section: BookSection): String? =
    cues(section.chapterId)?.textBetween(section.startMs, section.endMs + 1)?.takeUnless { it.isBlank() }

  /** Parses straight from the file, so a transcript of several MB is never held in memory as one string. */
  private suspend fun load(chapterId: ChapterId): List<TranscriptCue>? = withContext(Dispatchers.IO) {
    val file = files.transcriptFile(chapterId)
    if (!file.isFile) return@withContext null
    try {
      file.inputStream().use { TranscriptParser.parse(it) }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      Logger.w(e, "Could not read the transcript at $file")
      null
    } catch (e: OutOfMemoryError) {
      Logger.w(e, "Out of memory reading the transcript at $file")
      null
    }
  }
}
