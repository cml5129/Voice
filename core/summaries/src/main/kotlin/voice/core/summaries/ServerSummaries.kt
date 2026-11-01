package voice.core.summaries

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import voice.core.data.Book
import voice.core.logging.api.Logger
import voice.core.transcript.TranscriptFiles
import voice.core.transcript.sections
import kotlin.math.abs

/** What the server's transcriber writes as `<book>.summaries.json`. Times are positions in the audio file. */
@Serializable
internal data class ServerSummariesFile(
  val model: String = "",
  val chapters: List<ServerPartSummary> = emptyList(),
  val halfHours: List<ServerPartSummary> = emptyList(),
)

@Serializable
internal data class ServerPartSummary(
  val startMs: Long,
  val endMs: Long,
  val summary: String,
  val title: String? = null,
)

/**
 * The summaries the server wrote ahead of time, downloaded with the transcripts, by [SummaryPart.key]. Each covers
 * its whole part, so the screen shows one only once the listener has heard all of it.
 */
@SingleIn(AppScope::class)
@Inject
public class ServerSummaries(private val files: TranscriptFiles) {

  private val json = Json { ignoreUnknownKeys = true }

  public suspend fun forBook(book: Book): Map<String, SavedSummary> = withContext(Dispatchers.IO) {
    val byFile = book.chapters.associate { chapter -> chapter.id to read(files.summariesFile(chapter.id)) }
    val result = mutableMapOf<String, SavedSummary>()
    val parts = book.chapterParts()
    book.sections().forEachIndexed { index, section ->
      val server = byFile[section.chapterId] ?: return@forEachIndexed
      // the server reads the chapter marks with ffprobe, which may round them a little differently
      val match = server.chapters.firstOrNull { abs(it.startMs - section.startMs) <= MARK_TOLERANCE_MS } ?: return@forEachIndexed
      result[parts[index].key] = match.toSaved(server.model, parts[index].endMs)
    }
    // half hours are counted from the start of the file, which is the start of the book only with one file
    val single = book.chapters.singleOrNull()?.let { byFile[it.id] }
    if (single != null) {
      book.halfHourParts().forEach { part ->
        val match = single.halfHours.firstOrNull { it.startMs == part.startMs } ?: return@forEach
        result[part.key] = match.toSaved(single.model, part.endMs)
      }
    }
    result
  }

  private fun read(file: java.io.File): ServerSummariesFile? {
    if (!file.isFile) return null
    return try {
      json.decodeFromString(ServerSummariesFile.serializer(), file.readText())
    } catch (e: SerializationException) {
      Logger.w(e, "Could not read the server summaries at $file")
      null
    } catch (e: IllegalArgumentException) {
      Logger.w(e, "Could not read the server summaries at $file")
      null
    }
  }

  private fun ServerPartSummary.toSaved(
    model: String,
    endMs: Long,
  ) = SavedSummary(
    text = summary,
    coveredUntilMs = endMs,
    model = model.ifBlank { SummarySettings.SERVER },
    writtenAtMillis = 0,
  )

  private companion object {
    const val MARK_TOLERANCE_MS = 2_000L
  }
}
