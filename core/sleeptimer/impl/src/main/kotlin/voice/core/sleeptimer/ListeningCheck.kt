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

/**
 * During the automatic sleep timer's hours, turns the volume down every few minutes. A shake, a skip or
 * a seek says the listener is still awake. Without one they most likely fell asleep, so the book goes
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
  private val lostTrack: LostTrack,
  private val morningCheck: MorningCheck,
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
    val playing = playerController.livePlaybackState()?.position() ?: return
    var lostTrackAt = lostTrack.spot(playing.bookId)
    var awake = lostTrackAt ?: playing
    while (true) {
      // skipping or seeking shows they're awake right there, so the next check waits for the full interval again
      val acted = withTimeoutOrNull(CHECK_INTERVAL) { listenerActions().first() } != null
      if (acted) {
        val now = playerController.livePlaybackState()?.position() ?: return
        if (lostTrackAt == null) {
          awake = now
        } else if (lostTrack.isBefore(now, lostTrackAt)) {
          lostTrackAt = now
          awake = now
        }
        continue
      }
      if (!shouldCheck()) {
        lostTrackAt = null
        awake = playerController.livePlaybackState()?.position() ?: return
        continue
      }
      if (answered()) {
        playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer)
        sleepTimer.onListenerAwake()
        lostTrackAt = null
        awake = playerController.livePlaybackState()?.position() ?: return
      } else {
        Logger.i("Listening check missed")
        fellAsleep(awake)
        return
      }
    }
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

  private fun LivePlaybackState.position() = PlaybackPosition(bookId, chapterId, positionMs)

  internal companion object {
    val CHECK_INTERVAL = 10.minutes
    val ANSWER_TIME = 1.minutes
    const val PROMPT_VOLUME = 0.4F
  }
}
