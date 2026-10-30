package voice.features.summaries

import androidx.compose.runtime.Immutable
import voice.core.summaries.SummaryKind
import voice.core.summaries.SummaryPart
import voice.core.summaries.SummarySettings

@Immutable
internal data class SummariesViewState(
  val bookName: String,
  val cover: String?,
  val tab: SummaryKind,
  val parts: List<SummaryPartViewState>,
  /** The part playing now, to open the list there. */
  val currentIndex: Int?,
  val hasTranscript: Boolean,
  val settings: SummarySettings,
  val editingModel: Boolean,
)

@Immutable
internal data class SummaryPartViewState(
  val part: SummaryPart,
  /** The chapter's name or when the session was, null for half hours. */
  val title: String?,
  /** Like 1:30:00 – 2:00:00. */
  val range: String,
  val summary: String?,
  val heard: Boolean,
  val working: Boolean,
  /** Why the last try failed. */
  val error: String?,
  /** More of the part was heard since its summary was written. */
  val outdated: Boolean,
) {

  val canSummarize: Boolean get() = heard && !working && (summary == null || outdated)
}
