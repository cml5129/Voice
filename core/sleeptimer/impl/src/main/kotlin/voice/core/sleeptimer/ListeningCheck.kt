package voice.core.sleeptimer

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.history.ListeningHistoryRecorder
import voice.core.playback.history.PlaybackPosition
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState.Playing
import java.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * During the automatic sleep timer's hours, turns the volume down every few minutes. A shake, a volume
 * button, a skip or a seek says the listener is still awake. Without one they most likely fell asleep, so the book goes
 * back to where they last showed they were awake and pauses.
 *
 * Once they missed a check, the book keeps going back to where they lost track, see [LostTrack].
 */
@ContributesIntoSet(AppScope::class)
class ListeningCheck(
  private val sleepTimerPreferenceStore: SleepTimerPreferenceStore,
  private val playStateManager: PlayStateManager,
  private val playerController: PlayerController,
  private val shakeDetector: ShakeDetector,
  private val sleepTimer: SleepTimer,
  private val historyRecorder: ListeningHistoryRecorder,
  private val volumeChanges: VolumeChanges,
  private val lostTrack: LostTrack,
  private val morningCheck: MorningCheck,
  private val clock: Clock,
  private val scope: CoroutineScope,
) : AppInitializer {

  /** Where the listener last showed they were awake, kept after playback stops. */
  private var lastAwake: PlaybackPosition? = null

  override fun onAppStart(application: Application) {
    scope.launch {
      playStateManager.playStateFlow.collectLatest { playState ->
        if (playState == Playing) {
          checkWhilePlaying()
        }
      }
    }
    scope.launch {
      historyRecorder.events
        .filter { it.type == ListeningEvent.Type.SleepTimerEnded }
        .collect { onSleepTimerEnded() }
    }
  }

  private suspend fun checkWhilePlaying() {
    val playing = playerController.livePlaybackState()?.position() ?: return
    var lostTrackAt = lostTrack.spot(playing.bookId)
    var awake = lostTrackAt ?: playing
    lastAwake = awake
    while (true) {
      // skipping, seeking or a volume button shows they're awake right there, so the next check waits for the full
      // interval again
      val action = withTimeoutOrNull(CHECK_INTERVAL) {
        merge(listenerActions().map { Action.Navigated }, volumeChanges.changes.map { Action.ChangedVolume }).first()
      }
      if (action != null) {
        val now = playerController.livePlaybackState()?.position() ?: return
        if (lostTrackAt == null || action == Action.ChangedVolume) {
          lostTrackAt = null
          awake = now
        } else if (lostTrack.isBefore(now, lostTrackAt)) {
          lostTrackAt = now
          awake = now
        }
        lastAwake = awake
        continue
      }
      if (!shouldCheck()) {
        lostTrackAt = null
        awake = playerController.livePlaybackState()?.position() ?: return
        lastAwake = awake
        continue
      }
      if (answered()) {
        playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer)
        sleepTimer.onListenerAwake()
        lostTrackAt = null
        awake = playerController.livePlaybackState()?.position() ?: return
        lastAwake = awake
      } else {
        Logger.i("Listening check missed")
        fellAsleep(awake)
        return
      }
    }
  }

  /**
   * The sleep timer ran out without a sign of the listener in its whole countdown, so they most likely fell asleep,
   * and the book goes back to where they last were awake, as after a missed check.
   */
  private suspend fun onSleepTimerEnded() {
    val awake = lastAwake ?: return
    if (!shouldCheck()) return
    // a shake right after the timer ran out plays on
    delay(KEEP_LISTENING_TIME)
    if (playStateManager.playState == Playing) return
    Logger.i("Sleep timer ended, going back to where the listener was last awake")
    fellAsleep(awake)
  }

  private suspend fun answered(): Boolean {
    playerController.setVolume(PROMPT_VOLUME)
    return try {
      withTimeoutOrNull(ANSWER_TIME) {
        merge(flow { emit(shakeDetector.detect()) }, listenerActions().map {}, volumeChanges.changes).first()
      } != null
    } finally {
      playerController.setVolume(1F)
    }
  }

  private suspend fun fellAsleep(awake: PlaybackPosition) {
    if (playerController.livePlaybackState()?.bookId == awake.bookId) {
      playerController.setPosition(
        time = awake.time,
        id = awake.chapterId,
        type = ListeningEvent.Type.FellAsleep,
        source = ListeningEvent.Source.SleepTimer,
      )
    }
    playerController.pause()
    morningCheck.schedule(awake.bookId)
  }

  private fun listenerActions(): Flow<ListeningEvent> = historyRecorder.events.filter { it.isListenerAction() }

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

  private enum class Action {
    Navigated,
    ChangedVolume,
  }

  private fun LivePlaybackState.position() = PlaybackPosition(bookId, chapterId, positionMs)

  internal companion object {
    val CHECK_INTERVAL = 10.minutes
    val ANSWER_TIME = 1.minutes
    const val PROMPT_VOLUME = 0.4F

    /** A bit longer than the sleep timer listens for a shake after it ran out. */
    val KEEP_LISTENING_TIME = 45.seconds
  }
}
