package voice.features.summaries

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import voice.core.summaries.ServerSummaries
import voice.core.summaries.Summarizer
import voice.core.summaries.SummaryKind
import voice.core.summaries.SummaryPart
import voice.core.summaries.SummarySettings
import voice.core.summaries.SummarySettingsStore
import voice.core.summaries.SummaryStore
import voice.core.summaries.chapterParts
import voice.core.summaries.combinedPart
import voice.core.summaries.combinedParts
import voice.core.summaries.formatPosition
import voice.core.summaries.halfHourParts
import voice.core.summaries.heardUntil
import voice.core.summaries.sessionParts
import voice.core.transcript.TranscriptRepository
import voice.navigation.Navigator
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.minutes

@AssistedInject
class SummariesViewModel(
  private val bookRepository: BookRepository,
  private val historyRepo: ListeningHistoryRepo,
  private val settingsStore: SummarySettingsStore,
  private val summaryStore: SummaryStore,
  private val summarizer: Summarizer,
  private val serverSummaries: ServerSummaries,
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

  // picking the first and the last session to summarize together
  private var combining by mutableStateOf(false)
  private var combineFirst by mutableStateOf<SummaryPart?>(null)

  @Composable
  internal fun viewState(): SummariesViewState? {
    val book = remember { bookRepository.flow(bookId) }.collectAsState(initial = null).value ?: return null
    val settings = remember { settingsStore.data }.collectAsState(initial = null).value ?: return null
    val saved = remember { summaryStore.summaries(bookId) }.collectAsState(initial = emptyMap()).value
    val events = remember { historyRepo.events(bookId) }.collectAsState(initial = emptyList()).value
    val fromServer = produceState(initialValue = emptyMap<String, SavedSummary>(), book.chapters) {
      value = serverSummaries.forBook(book)
    }.value
    val hasTranscript = remember(book.chapters) { book.chapters.any { transcripts.hasTranscript(it.id) } }
    val sessions = remember(tab, book.chapters, events) {
      when (tab) {
        SummaryKind.Chapter -> book.chapterParts()
        SummaryKind.HalfHour -> book.halfHourParts()
        SummaryKind.Session, SummaryKind.Combined -> book.sessionParts(events, clock.millis())
      }
    }
    // sessions summarized together go on top, also while their summary is still being written
    val combined = if (tab == SummaryKind.Session) combinedParts(saved.keys + working + errors.keys) else emptyList()
    val parts = combined + sessions
    val position = book.position
    return SummariesViewState(
      bookName = book.content.name,
      cover = book.content.coverUrl,
      tab = tab,
      parts = parts.map { part -> part.viewState(book, pick(part, position, saved[part.key], fromServer[part.key])) },
      currentIndex = parts.indexOfFirst { position >= it.startMs && position < it.endMs }
        .takeIf { it >= 0 && tab != SummaryKind.Session },
      hasTranscript = hasTranscript,
      settings = settings,
      editingModel = editingModel,
      canCombine = tab == SummaryKind.Session && sessions.size >= 2,
      combining = combining,
      combineFirstPicked = combineFirst != null,
    )
  }

  /**
   * The server's summary covers its whole part, so it only shows once all of the part was heard. One written on
   * request is kept while it covers as much.
   */
  private fun pick(
    part: SummaryPart,
    position: Long,
    saved: SavedSummary?,
    fromServer: SavedSummary?,
  ): SavedSummary? {
    if (fromServer == null || part.endMs > position) return saved
    return if (saved == null || saved.coveredUntilMs < fromServer.coveredUntilMs) fromServer else saved
  }

  private fun SummaryPart.viewState(
    book: Book,
    saved: SavedSummary?,
  ): SummaryPartViewState {
    val heardUntil = heardUntil(book, this)
    return SummaryPartViewState(
      part = this,
      title = when (kind) {
        SummaryKind.Chapter -> name
        SummaryKind.HalfHour -> null
        SummaryKind.Session -> startedAt?.let(::formatStart)
        SummaryKind.Combined -> null
      },
      range = "${formatPosition(startMs)} – ${formatPosition(endMs)}",
      summary = saved?.text,
      heard = heardUntil > startMs,
      working = key in working,
      error = errors[key],
      outdated = saved != null && heardUntil - saved.coveredUntilMs >= OUTDATED_MS,
      pickable = combining && kind == SummaryKind.Session,
      picked = combineFirst?.key == key,
    )
  }

  private fun formatStart(at: Instant): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .format(at.atZone(clock.zone))

  internal fun onTabChange(kind: SummaryKind) {
    tab = kind
    onCombineCancel()
  }

  internal fun onCombineClick() {
    combining = true
    combineFirst = null
  }

  internal fun onCombineCancel() {
    combining = false
    combineFirst = null
  }

  /** The first tap picks where to begin, the second where to end; everything in between is included. */
  internal fun onSessionPick(part: SummaryPart) {
    if (!combining || part.kind != SummaryKind.Session) return
    val first = combineFirst
    if (first == null) {
      combineFirst = part
      return
    }
    if (first.key == part.key) {
      combineFirst = null
      return
    }
    onCombineCancel()
    onSummarize(combinedPart(first, part))
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
