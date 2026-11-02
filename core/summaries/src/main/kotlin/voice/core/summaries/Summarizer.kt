package voice.core.summaries

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Book
import voice.core.server.VoiceServerConfig
import voice.core.transcript.ServerResult
import voice.core.transcript.ServerSummaryPart
import voice.core.transcript.ServerTranscriptsApi
import voice.core.transcript.TranscriptRepository
import voice.core.transcript.audioFileName
import voice.core.transcript.textBetween
import java.time.Clock
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Writes the summary of a part of a book from its transcript, with the model the listener picked. Nothing past where
 * the listener is goes into it, so a summary never gives away what comes next.
 */
@SingleIn(AppScope::class)
@Inject
public class Summarizer(
  private val settings: SummarySettingsStore,
  private val models: SummaryModels,
  private val transcripts: TranscriptRepository,
  private val store: SummaryStore,
  private val clock: Clock,
  private val serverConfig: VoiceServerConfig,
  private val api: ServerTranscriptsApi,
) {

  // one at a time, a local model can't do more anyway
  private val lock = Mutex()

  /** Whether some of [part] was heard, so there is something to summarize. */
  public fun canSummarize(
    book: Book,
    part: SummaryPart,
  ): Boolean = heardUntil(book, part) > part.startMs

  public suspend fun summarize(
    book: Book,
    part: SummaryPart,
  ): SavedSummary = lock.withLock {
    val settings = settings.data.first()
    val until = heardUntil(book, part)
    if (until <= part.startMs) throw SummaryException("You haven't listened to this part yet")
    val text = if (settings.provider == SummaryProvider.Server) {
      askServer(book, part, until)
    } else {
      val transcript = book.text(part.startMs, until) ?: throw SummaryException("There's no transcript for this part yet")
      write(models.model(settings), book, part, until, transcript)
    }
    val summary = SavedSummary(
      text = text,
      coveredUntilMs = until,
      model = settings.modelName,
      writtenAtMillis = clock.millis(),
    )
    store.save(book.id, part.key, summary)
    summary
  }

  private suspend fun write(
    model: SummaryModel,
    book: Book,
    part: SummaryPart,
    until: Long,
    text: String,
  ): String {
    val words = part.words()
    val about = "${book.about()}\n${part.describe(until)}"
    val pieces = splitText(text, model.maxInputChars)
    if (pieces.size == 1) {
      return model.complete(summarySystem(words), "$about\n\nTranscript:\n\"\"\"\n$text\n\"\"\"")
    }
    // too long for one request: summarize stretch by stretch, then put those together
    val notes = pieces.mapIndexed { index, piece ->
      model.complete(
        summarySystem(NOTE_WORDS),
        "$about\nStretch ${index + 1} of ${pieces.size}.\n\nTranscript:\n\"\"\"\n$piece\n\"\"\"",
      )
    }
    val joined = notes.mapIndexed { index, note -> "Stretch ${index + 1}:\n$note" }.joinToString("\n\n")
    return model.complete(combineSystem(words), "$about\n\n$joined")
  }

  /**
   * Has the listener's server write the summary from its own copy of the transcript, with its local model. A long
   * stretch goes in pieces the server can each do in one request, and the server then puts their notes together.
   */
  private suspend fun askServer(
    book: Book,
    part: SummaryPart,
    until: Long,
  ): String {
    val server = serverConfig.current() ?: throw SummaryException("Set up your server in Settings first")
    val label = part.describe(until)
    val pieces = (part.startMs until until step SERVER_PIECE_MS)
      .map { start -> start to min(start + SERVER_PIECE_MS, until) }
      .map { (start, end) -> book.serverParts(start, end) }
      .filter { it.isNotEmpty() }
    if (pieces.isEmpty()) throw SummaryException("There's no transcript for this part yet")
    if (pieces.size == 1) return api.summarize(server, book.title(), label, pieces.single()).orThrow()
    val notes = pieces.mapIndexed { index, parts ->
      api.summarize(server, book.title(), "$label\nStretch ${index + 1} of ${pieces.size}.", parts).orThrow()
    }
    return api.combine(server, book.title(), label, notes).orThrow()
  }

  /** The stretch between two positions in the whole book, as positions in each audio file. */
  private fun Book.serverParts(
    startMs: Long,
    endMs: Long,
  ): List<ServerSummaryPart> {
    val starts = fileStarts()
    return chapters.mapNotNull { chapter ->
      val fileStart = starts.getValue(chapter.id)
      val from = max(startMs - fileStart, 0)
      val to = min(endMs - fileStart, chapter.duration)
      if (from >= to) return@mapNotNull null
      val file = audioFileName(chapter.id) ?: return@mapNotNull null
      ServerSummaryPart(file = file, startMs = from, endMs = to)
    }
  }

  private fun ServerResult<String>.orThrow(): String = when (this) {
    is ServerResult.Success -> value
    is ServerResult.Failure -> throw SummaryException(reason)
  }

  /** The transcript between two positions in the whole book, or null where there is none. */
  private suspend fun Book.text(
    startMs: Long,
    endMs: Long,
  ): String? {
    val starts = fileStarts()
    val texts = chapters.mapNotNull { chapter ->
      val fileStart = starts.getValue(chapter.id)
      val from = max(startMs - fileStart, 0)
      val to = min(endMs - fileStart, chapter.duration)
      if (from >= to) return@mapNotNull null
      transcripts.cues(chapter.id)?.textBetween(from, to)
    }
    return texts.joinToString(" ").trim().takeUnless { it.isBlank() }
  }
}

/**
 * How far into the book the summary of [part] can go. Sessions were heard to their end even after going back, so
 * only chapters and half hours stop where the listener is.
 */
public fun heardUntil(
  book: Book,
  part: SummaryPart,
): Long = when (part.kind) {
  SummaryKind.Session, SummaryKind.Combined -> part.endMs
  SummaryKind.Chapter, SummaryKind.HalfHour -> min(part.endMs, book.position)
}

private fun SummaryPart.words(): Int = when (kind) {
  SummaryKind.Chapter, SummaryKind.HalfHour -> PART_WORDS
  SummaryKind.Session -> SESSION_WORDS
  SummaryKind.Combined -> COMBINED_WORDS
}

/** Each fits one request to the server, well within the time the connection allows. */
private const val SERVER_PIECE_MS = 90 * 60 * 1000L

internal const val PART_WORDS = 120
internal const val SESSION_WORDS = 150
internal const val COMBINED_WORDS = 200
internal const val NOTE_WORDS = 150

internal fun summarySystem(words: Int): String = "You summarize part of an audiobook from a rough speech-to-text " +
  "transcript, for a listener who has heard this part and wants to remember it. In at most $words words of plain " +
  "prose, say what happens or what is explained: the people, places and events, and anything important that comes " +
  "out. Use only this transcript. Never mention or hint at anything from later in the book, even if you know it. " +
  "Names in the transcript may be misheard; fix the spelling only when you're sure. No headings, no lists."

internal fun combineSystem(words: Int): String = "You get notes on consecutive stretches of one part of an " +
  "audiobook, in order. Turn them into one summary of at most $words words of plain prose, for a listener who has " +
  "heard this part and wants to remember it. Use only the notes. Never mention or hint at anything from later in " +
  "the book, even if you know it. No headings, no lists."

private fun Book.title(): String {
  val author = content.metadata.author?.takeIf { it.isNotBlank() }
  return if (author == null) content.name else "${content.name} by $author"
}

private fun Book.about(): String = "Book: ${title()}"

private fun SummaryPart.describe(until: Long): String {
  val stretch = "${formatPosition(startMs)} to ${formatPosition(until)} of the book"
  val partial = if (until < endMs) ", where the listener is now" else ""
  return when (kind) {
    SummaryKind.Chapter -> "Chapter \"$name\" ($stretch$partial)."
    SummaryKind.HalfHour -> "The half hour from $stretch$partial."
    SummaryKind.Session -> "One listening session, from $stretch."
    SummaryKind.Combined -> "Several listening sessions in a row, from $stretch."
  }
}

/** Like 1:05:09. */
public fun formatPosition(ms: Long): String {
  val seconds = ms / 1000
  return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** Splits at spaces into pieces of at most about [maxChars]. */
internal fun splitText(
  text: String,
  maxChars: Int,
): List<String> {
  if (text.length <= maxChars) return listOf(text)
  val pieces = mutableListOf<String>()
  var start = 0
  while (start < text.length) {
    var end = min(start + maxChars, text.length)
    if (end < text.length) {
      val space = text.lastIndexOf(' ', end)
      if (space > start) end = space
    }
    pieces += text.substring(start, end).trim()
    start = end
  }
  return pieces.filter { it.isNotEmpty() }
}
