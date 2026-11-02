package voice.features.readAlong

import androidx.compose.runtime.Immutable
import voice.core.data.ChapterId
import voice.core.transcript.TranscriptCue

@Immutable
internal data class ReadAlongViewState(
  val bookName: String,
  val cover: String?,
  /** The chapter playing now, null for a book without chapter names. */
  val chapterName: String?,
  /** The audio file playing now, whose transcript is shown. */
  val chapterId: ChapterId,
  /** Null while the transcript is read, empty when the file has none. */
  val cues: List<TranscriptCue>?,
  /** The line being read, -1 before the first one. */
  val currentIndex: Int,
  val playing: Boolean,
)
