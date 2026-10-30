package voice.core.transcript

import voice.core.data.Book
import voice.core.data.ChapterId

/** A chapter as the listener sees it: one chapter mark inside one audio file, in book order. */
data class BookSection(
  val index: Int,
  val chapterId: ChapterId,
  val name: String,
  /** Where the section starts and ends inside its audio file. */
  val startMs: Long,
  val endMs: Long,
) {

  /** Stays the same across rescans, so summaries can be stored against it. */
  val key: String get() = "${chapterId.value}@$startMs"
}

fun Book.sections(): List<BookSection> = chapters
  .flatMap { chapter -> chapter.chapterMarks.map { mark -> chapter.id to mark } }
  .mapIndexed { index, (chapterId, mark) ->
    BookSection(
      index = index,
      chapterId = chapterId,
      name = mark.name?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}",
      startMs = mark.startMs,
      endMs = mark.endMs,
    )
  }

/** The section playing now. */
fun Book.currentSection(): BookSection {
  val sections = sections()
  return sections.lastOrNull { it.chapterId == currentChapter.id && it.startMs <= content.positionInChapter }
    ?: sections.first()
}

/** The text of the cues that start between [startMs] and [endMs]. */
fun List<TranscriptCue>.textBetween(
  startMs: Long,
  endMs: Long,
): String = asSequence()
  .filter { it.timestampStartMs >= startMs && it.timestampStartMs < endMs }
  .joinToString(separator = " ") { it.text.trim() }
