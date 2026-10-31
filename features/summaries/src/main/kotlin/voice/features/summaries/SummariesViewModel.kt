package voice.features.summaries

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.logging.api.Logger
import voice.core.summaries.SavedSummary
import voice.core.summaries.Summarizer
import voice.core.summaries.SummaryKind
import voice.core.summaries.SummaryPart
import voice.core.summaries.SummarySettings
import voice.core.summaries.SummarySettingsStore
import voice.core.summaries.SummaryStore
import voice.core.summaries.chapterParts
import voice.core.summaries.formatPosition
import voice.core.summaries.halfHourParts
import voice.core.summaries.sessionParts
import voice.core.transcript.TranscriptRepository
import voice.navigation.Navigator
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes

@AssistedInject
class SummariesViewModel(
  private val bookRepository: BookRepository,
  private val historyRepo: ListeningHistoryRepo,
  private val settingsStore: SummarySettingsStore,
  private val summaryStore: SummaryStore,
  private val summarizer: Summarizer,
  private val transcripts: TranscriptRepository,
  private val navigator: Navigator,
  private val clock: Clock,
  dispatcherProvider: DispatcherProvider,
  @Assisted
  private val bookId: BookId,
) {

  // a summary goes on when the screen is left, and is saved for next time
  private val scope = MainScope(dispatcherProvider)

  private var tab by mutableStateOf(SummaryKind.Chapter)
  private var working by mutableStateOf(emptySet<String>())
  private var errors by mutableStateOf(emptyMap<String, String>())
  private var editingModel by mutableStateOf(false)

  @Composable
  internal fun viewState(): SummariesViewState? {
    val book = remember { bookRepository.flow(bookId) }.collectAsState(initial = null).value ?: return null
    val settings = remember { settingsStore.data }.collectAsState(initial = null).value ?: return null
    val saved = remember { summaryStore.summaries(bookId) }.collectAsState(initial = emptyMap()).value
    val events = remember { historyRepo.events(bookId) }.collectAsState(initial = emptyList()).value
    val hasTranscript = remember(book.chapters) { book.chapters.any { transcripts.hasTranscript(it.id) } }
    val parts = remember(tab, book.chapters, events) {
      when (tab) {
        SummaryKind.Chapter -> book.chapterParts()
        SummaryKind.HalfHour -> book.halfHourParts()
        SummaryKind.Session -> book.sessionParts(events, clock.millis())
      }
    }
    val position = book.position
    return SummariesViewState(
      bookName = book.content.name,
      cover = book.content.coverUrl,
      tab = tab,
      parts = parts.map { part -> part.viewState(position, saved[part.key]) },
      currentIndex = parts.indexOfFirst { position >= it.startMs && position < it.endMs }
        .takeIf { it >= 0 && tab != SummaryKind.Session },
      hasTranscript = hasTranscript,
      settings = settings,
      editingModel = editingModel,
    )
  }

  private fun SummaryPart.viewState(
    position: Long,
    saved: SavedSummary?,
  ): SummaryPartViewState {
    val heardUntil = min(endMs, position)
    return SummaryPartViewState(
      part = this,
      title = when (kind) {
        SummaryKind.Chapter -> name
        SummaryKind.HalfHour -> null
        SummaryKind.Session -> startedAt?.let(::formatStart)
      },
      range = "${formatPosition(startMs)} – ${formatPosition(endMs)}",
      summary = saved?.text,
      heard = startMs < position,
      working = key in working,
      error = errors[key],
      outdated = saved != null && heardUntil - saved.coveredUntilMs >= OUTDATED_MS,
    )
  }

  private fun formatStart(at: Instant): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .format(at.atZone(clock.zone))

  internal fun onTabChange(kind: SummaryKind) {
    tab = kind
  }

  internal fun onSummarize(part: SummaryPart) {
    if (part.key in working) return
    working = working + part.key
    errors = errors - part.key
    scope.launch {
      try {
        val book: Book = bookRepository.get(bookId) ?: return@launch
        val _ = summarizer.summarize(book, part)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Could not summarize ${part.key}")
        errors = errors + (part.key to (e.message ?: e.javaClass.simpleName))
      } finally {
        working = working - part.key
      }
    }
  }

  internal fun onModelClick() {
    editingModel = true
  }

  internal fun onModelDismiss() {
    editingModel = false
  }

  internal fun onModelSave(settings: SummarySettings) {
    onModelDismiss()
    scope.launch {
      val _ = settingsStore.updateData { settings }
    }
  }

  internal fun onCloseClick() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(bookId: BookId): SummariesViewModel
  }
}

/** Shorter progress isn't worth writing the summary again. */
private val OUTDATED_MS = 2.minutes.inWholeMilliseconds
