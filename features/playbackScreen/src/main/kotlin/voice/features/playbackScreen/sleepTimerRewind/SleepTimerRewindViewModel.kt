package voice.features.playbackScreen.sleepTimerRewind

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.playback.PlayerController
import voice.core.sleeptimer.SleepTimerRewindStore

internal data class SleepTimerRewindViewState(val options: List<RewindOption>)

@Inject
class SleepTimerRewindViewModel(
  private val sleepTimerRewindStore: SleepTimerRewindStore,
  private val currentBookStore: CurrentBookStore,
  private val bookRepository: BookRepository,
  private val player: PlayerController,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)

  @Composable
  internal fun viewState(): SleepTimerRewindViewState? {
    val rewind = remember { sleepTimerRewindStore.data }.collectAsState(initial = null).value
      ?: return null
    val currentBookId = remember { currentBookStore.data }.collectAsState(initial = null).value
    // the player only seeks within the current book, and playing another one clears the offer anyway
    if (rewind.bookId != currentBookId) return null
    return SleepTimerRewindViewState(options = rewind.rewindOptions())
  }

  internal fun onRewind(option: RewindOption) {
    scope.launch {
      val rewind = sleepTimerRewindStore.data.first() ?: return@launch
      val book = bookRepository.get(rewind.bookId)
      if (book != null) {
        val position = book.chapterPosition(option.positionInBookMs)
        player.setPosition(time = position.positionInChapterMs, id = position.chapterId)
      }
      sleepTimerRewindStore.updateData { null }
    }
  }

  internal fun onDismiss() {
    scope.launch {
      sleepTimerRewindStore.updateData { null }
    }
  }
}
