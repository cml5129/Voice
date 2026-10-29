package voice.core.sleeptimer

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.data.ListeningEvent
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.history.ListeningHistoryRecorder
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState.Playing
import java.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * During the automatic sleep timer's hours, turns the volume down every few minutes. A shake, a skip or
 * a seek says the listener is still awake. Without one they most likely fell asleep, so the book goes
 * back to where they last showed they were awake and pauses.
 *
 * Once they missed a check, they lost track of the book from there on. Pressing play again in the
 * night, or skipping ahead, can happen half asleep, so until they answer a check, the book keeps going
 * back to the earliest spot they lost track of, or to where they went back to on their own.
 */
@ContributesIntoSet(AppScope::class)
class ListeningCheck(
  private val sleepTimerPreferenceStore: SleepTimerPreferenceStore,
  private val playStateManager: PlayStateManager,
  private val playerController: PlayerController,
  private val shakeDetector: ShakeDetector,
  private val sleepTimer: SleepTimer,
  private val historyRecorder: ListeningHistoryRecorder,
  private val historyRepo: ListeningHistoryRepo,
  private val bookRepository: BookRepository,
  private val clock: Clock,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    scope.launch {
      playStateManager.playStateFlow.collectLatest { playState ->
        if (playState == Playing) {
          checkWhilePlaying()
        }
      }
    }
  }

  private suspend fun checkWhilePlaying() {
    val playing = playerController.livePlaybackState() ?: return
    var lostTrackAt = lostTrackTonight(playing)
    var awake = lostTrackAt ?: playing
    while (true) {
      // skipping or seeking shows they're awake right there, so the next check waits for the full interval again
      val acted = withTimeoutOrNull(CHECK_INTERVAL) { listenerActions().first() } != null
      if (acted) {
        val now = playerController.livePlaybackState() ?: return
        if (lostTrackAt == null) {
          awake = now
        } else if (isBefore(now, lostTrackAt)) {
          lostTrackAt = now
          awake = now
        }
        continue
      }
      if (!shouldCheck()) {
        lostTrackAt = null
        awake = playerController.livePlaybackState() ?: return
        continue
      }
      if (answered()) {
        playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer)
        sleepTimer.onListenerAwake()
        lostTrackAt = null
        awake = playerController.livePlaybackState() ?: return
      } else {
        Logger.i("Listening check missed")
        fellAsleep(awake)
        return
      }
    }
  }

  /**
   * Where the listener lost track of the book, if they missed a check in the last hours and haven't
   * answered one since.
   */
  private suspend fun lostTrackTonight(playing: LivePlaybackState): LivePlaybackState? {
    val since = clock.millis() - LOST_TRACK_FOR.inWholeMilliseconds
    val events = historyRepo.events(playing.bookId).first()
      .takeWhile { it.atMillis >= since }
      .asReversed()
    var lostTrackAt: LivePlaybackState? = null
    for (event in events) {
      val to = playing.copy(chapterId = event.toChapterId ?: event.chapterId, positionMs = event.toTime ?: event.time)
      lostTrackAt = when {
        event.type == ListeningEvent.Type.StillListening -> null
        event.type == ListeningEvent.Type.FellAsleep -> lostTrackAt?.takeUnless { isBefore(to, it) } ?: to
        lostTrackAt != null && event.isListenerAction() && isBefore(to, lostTrackAt) -> to
        else -> lostTrackAt
      }
    }
    return lostTrackAt
  }

  private suspend fun isBefore(
    a: LivePlaybackState,
    b: LivePlaybackState,
  ): Boolean {
    if (a.chapterId == b.chapterId) return a.positionMs < b.positionMs
    val chapters = bookRepository.get(a.bookId)?.chapters?.map { it.id } ?: return false
    val indexA = chapters.indexOf(a.chapterId)
    val indexB = chapters.indexOf(b.chapterId)
    return indexA >= 0 && indexB >= 0 && indexA < indexB
  }

  private suspend fun answered(): Boolean {
    playerController.setVolume(PROMPT_VOLUME)
    return try {
      withTimeoutOrNull(ANSWER_TIME) {
        merge(flow { emit(shakeDetector.detect()) }, listenerActions().map {}).first()
      } != null
    } finally {
      playerController.setVolume(1F)
    }
  }

  private suspend fun fellAsleep(awake: LivePlaybackState) {
    if (playerController.livePlaybackState()?.bookId == awake.bookId) {
      playerController.setPosition(
        time = awake.positionMs,
        id = awake.chapterId,
        type = ListeningEvent.Type.FellAsleep,
        source = ListeningEvent.Source.SleepTimer,
      )
    }
    playerController.pause()
  }

  private fun listenerActions(): Flow<ListeningEvent> = historyRecorder.events.filter { it.isListenerAction() }

  private fun ListeningEvent.isListenerAction(): Boolean = source in LISTENER_SOURCES && type in LISTENER_ACTIONS

  private suspend fun shouldCheck(): Boolean {
    val preference = sleepTimerPreferenceStore.data.first()
    return preference.autoSleepTimerEnabled &&
      preference.listeningCheckEnabled &&
      isTimeInRange(
        currentTime = clock.instant().atZone(clock.zone).toLocalTime(),
        startTime = preference.autoSleepStartTime,
        endTime = preference.autoSleepEndTime,
      )
  }

  internal companion object {
    val CHECK_INTERVAL = 10.minutes
    val ANSWER_TIME = 1.minutes
    const val PROMPT_VOLUME = 0.4F
    val LOST_TRACK_FOR = 12.hours
    private val LISTENER_SOURCES = setOf(
      ListeningEvent.Source.App,
      ListeningEvent.Source.Widget,
      ListeningEvent.Source.Notification,
      ListeningEvent.Source.Headset,
      ListeningEvent.Source.Bluetooth,
      ListeningEvent.Source.Car,
      ListeningEvent.Source.Watch,
    )
    private val LISTENER_ACTIONS = setOf(
      ListeningEvent.Type.Play,
      ListeningEvent.Type.Seek,
      ListeningEvent.Type.SkipBack,
      ListeningEvent.Type.SkipForward,
      ListeningEvent.Type.ChapterChange,
      ListeningEvent.Type.BookmarkJump,
      ListeningEvent.Type.JumpBack,
    )
  }
}
