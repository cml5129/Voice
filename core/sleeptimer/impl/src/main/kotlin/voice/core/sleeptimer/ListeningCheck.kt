package voice.core.sleeptimer

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.data.ListeningEvent
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState.Playing
import java.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * During the automatic sleep timer's hours, turns the volume down every few minutes. A shake says the
 * listener is still awake. Without one they most likely fell asleep, so the book goes back to where
 * they last answered and pauses.
 */
@ContributesIntoSet(AppScope::class)
class ListeningCheck(
  private val sleepTimerPreferenceStore: SleepTimerPreferenceStore,
  private val playStateManager: PlayStateManager,
  private val playerController: PlayerController,
  private val shakeDetector: ShakeDetector,
  private val sleepTimer: SleepTimer,
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
    var awake = playerController.livePlaybackState() ?: return
    while (true) {
      delay(CHECK_INTERVAL)
      if (!shouldCheck()) {
        awake = playerController.livePlaybackState() ?: return
        continue
      }
      if (answered()) {
        playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer)
        sleepTimer.onListenerAwake()
        awake = playerController.livePlaybackState() ?: return
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
        shakeDetector.detect()
        true
      } ?: false
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
  }
}
