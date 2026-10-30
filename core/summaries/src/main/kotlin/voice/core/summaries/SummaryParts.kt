package voice.core.summaries

import voice.core.data.Book
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent
import voice.core.transcript.sections
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes

public enum class SummaryKind {
  Chapter,
  HalfHour,
  Session,
}

/** A stretch of a book to summarize. [startMs] and [endMs] are positions in the whole book. */
public data class SummaryPart(
  val kind: SummaryKind,
  /** Stays the same across rescans, so the summary can be stored against it. */
  val key: String,
  val startMs: Long,
  val endMs: Long,
  /** The chapter's name, for [SummaryKind.Chapter]. */
  val name: String? = null,
  /** When the listening began, for [SummaryKind.Session]. */
  val startedAt: Instant? = null,
)

internal val HALF_HOUR_MS = 30.minutes.inWholeMilliseconds

/** Shorter pauses don't end a listening session, like in the history. */
internal val SESSION_GAP_MS = 30.minutes.inWholeMilliseconds

/** Shorter listening isn't worth a summary. */
internal val MIN_SESSION_MS = 1.minutes.inWholeMilliseconds

/** No one listens faster, so a session can't have covered more of the book than this allows. */
private const val FASTEST_SPEED = 3.5

private const val MAX_SESSIONS = 50

/** Where each audio file starts in the whole book. */
internal fun Book.fileStarts(): Map<ChapterId, Long> {
  var start = 0L
  return chapters.associate { chapter -> (chapter.id to start).also { start += chapter.duration } }
}

public fun Book.chapterParts(): List<SummaryPart> {
  val starts = fileStarts()
  return sections().map { section ->
    val fileStart = starts.getValue(section.chapterId)
    SummaryPart(
      kind = SummaryKind.Chapter,
      key = "chapter:${section.key}",
      startMs = fileStart + section.startMs,
      endMs = fileStart + section.endMs + 1,
      name = section.name,
    )
  }
}

public fun Book.halfHourParts(): List<SummaryPart> {
  val count = ((duration + HALF_HOUR_MS - 1) / HALF_HOUR_MS).toInt()
  return (0 until count).map { index ->
    SummaryPart(
      kind = SummaryKind.HalfHour,
      key = "half:$index",
      startMs = index * HALF_HOUR_MS,
      endMs = min((index + 1) * HALF_HOUR_MS, duration),
    )
  }
}

/**
 * The listening sessions of this book, newest first. A session ends at a pause of 30 minutes or more, and where the
 * listener fell asleep. [nowMillis] ends a session that is still playing.
 */
public fun Book.sessionParts(
  events: List<ListeningEvent>,
  nowMillis: Long,
): List<SummaryPart> {
  val starts = fileStarts()
  fun positionOf(
    chapterId: ChapterId?,
    time: Long?,
  ): Long? {
    if (chapterId == null || time == null) return null
    return starts[chapterId]?.plus(time)
  }

  val sorted = events.filter { it.bookId == id }.sortedWith(compareBy({ it.atMillis }, { it.id }))
  val sessions = mutableListOf<MutableList<ListeningEvent>>()
  sorted.forEachIndexed { index, event ->
    val previous = sorted.getOrNull(index - 1)
    if (previous == null || event.atMillis - previous.atMillis >= SESSION_GAP_MS) {
      sessions += mutableListOf(event)
    } else {
      sessions.last() += event
    }
  }
  return sessions.mapIndexedNotNull { index, session ->
    val first = session.first()
    val asleep = session.firstOrNull { it.type == ListeningEvent.Type.FellAsleep }
    val last = asleep ?: session.last()
    val stillPlaying = asleep == null && index == sessions.lastIndex && last.type == ListeningEvent.Type.Play
    val endedAt = if (stillPlaying) max(nowMillis, last.atMillis) else last.atMillis
    val end = if (stillPlaying) {
      this.position
    } else {
      positionOf(last.toChapterId, last.toTime) ?: positionOf(last.chapterId, last.time)
    } ?: return@mapIndexedNotNull null
    val earliest = session.takeWhile { it !== asleep }
      .ifEmpty { listOf(first) }
      .mapNotNull { positionOf(it.chapterId, it.time) }
      .minOrNull() ?: return@mapIndexedNotNull null
    // a slip to the start of the book would make the session look far longer than it was
    val start = max(earliest, end - ((endedAt - first.atMillis) * FASTEST_SPEED).toLong())
    if (end - start < MIN_SESSION_MS) return@mapIndexedNotNull null
    SummaryPart(
      kind = SummaryKind.Session,
      key = "session:${first.id}",
      startMs = start,
      endMs = end,
      startedAt = Instant.ofEpochMilli(first.atMillis),
    )
  }.asReversed().take(MAX_SESSIONS)
}
