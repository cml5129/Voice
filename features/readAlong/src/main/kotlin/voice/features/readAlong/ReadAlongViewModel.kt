package voice.features.readAlong

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.filterNotNull
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent
import voice.core.data.markForPosition
import voice.core.data.repo.BookRepository
import voice.core.playback.PlayerController
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager
import voice.core.transcript.TranscriptCue
import voice.core.transcript.TranscriptRepository
import voice.core.transcript.cueIndexAt
import voice.navigation.Navigator

@AssistedInject
class ReadAlongViewModel(
  private val bookRepository: BookRepository,
  private val player: PlayerController,
  private val playStateManager: PlayStateManager,
  private val transcripts: TranscriptRepository,
  private val navigator: Navigator,
  @Assisted
  private val bookId: BookId,
) {

  @Composable
  internal fun viewState(): ReadAlongViewState? {
    val persistedBook = remember(bookId) { bookRepository.flow(bookId).filterNotNull() }
      .collectAsState(initial = null).value ?: return null
    // the player's own position, a few times a second, so the highlight keeps up with the voice
    val live = remember(bookId) { player.livePlaybackStateFlow(bookId) }.collectAsState(initial = null).value
    val managerPlayState = remember { playStateManager.playStateFlow }.collectAsState().value
    val book = if (live != null) persistedBook.overlay(live) else persistedBook
    val chapterId = book.currentChapter.id
    val cues = produceState<List<TranscriptCue>?>(initialValue = null, chapterId) {
      value = transcripts.cues(chapterId).orEmpty()
    }.value
    val position = book.content.positionInChapter
    val hasChapterNames = book.chapters.sumOf { it.chapterMarks.size } > 1
    return ReadAlongViewState(
      bookName = book.content.name,
      cover = book.content.coverUrl,
      chapterName = book.currentChapter.markForPosition(position).name
        ?.takeIf { hasChapterNames && it.isNotBlank() },
      chapterId = chapterId,
      cues = cues,
      currentIndex = cues?.cueIndexAt(position) ?: -1,
      playing = live?.isPlaying ?: (managerPlayState == PlayStateManager.PlayState.Playing),
    )
  }

  internal fun onCueClick(
    chapterId: ChapterId,
    cue: TranscriptCue,
  ) {
    player.setPosition(cue.timestampStartMs, chapterId, ListeningEvent.Type.Seek)
  }

  internal fun playPause() {
    player.playPause()
  }

  internal fun rewind() {
    player.rewind()
  }

  internal fun fastForward() {
    player.fastForward()
  }

  internal fun onCloseClick() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(bookId: BookId): ReadAlongViewModel
  }
}
