package voice.core.sleeptimer

import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.ListeningEvent
import voice.core.logging.api.Logger
import voice.core.playback.PlayerController
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState.Playing
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SleepTimerImpl internal constructor(
  private val playStateManager: PlayStateManager,
  private val shakeDetector: ShakeDetector,
  private val sleepTimerPreferenceStore: SleepTimerPreferenceStore,
  private val playerController: PlayerController,
  private val fadeOutStore: FadeOutStore,
  dispatcherProvider: DispatcherProvider,
  private val tracker: SleepTimerTracker,
  private val rewindRecorder: SleepTimerRewindRecorder,
) : SleepTimer {

  private val scope = MainScope(dispatcherProvider)
  override val state: StateFlow<SleepTimerState>
    field = MutableStateFlow<SleepTimerState>(SleepTimerState.Disabled)

  private var job: Job? = null

  /** Set by [onListenerAwake], picked up by the running countdown with its next tick. */
  private var awake = false

  override fun enable(mode: SleepTimerMode) {
    tracker.enabled(mode)
    disable() // cancel any active job first
    playerController.record(
      ListeningEvent.Type.SleepTimerStarted,
      if (mode == SleepTimerMode.TimedWithDefault) ListeningEvent.Source.SleepTimer else ListeningEvent.Source.App,
    )

    job = scope.launch {
      when (mode) {
        is SleepTimerMode.TimedWithDuration -> startCountdown(mode.duration)
        SleepTimerMode.TimedWithDefault -> {
          // the default duration is only used when the sleep timer turns on by itself
          startCountdown(sleepTimerPreferenceStore.data.first().duration)
        }
        SleepTimerMode.EndOfChapter -> {
          state.value = SleepTimerState.Enabled.WithEndOfChapter
          disableWhenPausedForGood()
        }
      }
    }
  }

  override fun disable() {
    tracker.disabled()
    job?.cancel()
    job = null
    awake = false
    state.value = SleepTimerState.Disabled
    playerController.setVolume(1F)
  }

  override fun onListenerAwake() {
    if (state.value is SleepTimerState.Enabled.WithDuration) {
      awake = true
    }
  }

  private tailrec suspend fun startCountdown(duration: Duration) {
    Logger.d("startCountdown(duration=$duration)")
    var left = duration
    state.value = SleepTimerState.Enabled.WithDuration(left)
    playerController.setVolume(1F)
    awake = false
    rewindRecorder.onCountdownStarted()

    val fadeOutDuration = fadeOutStore.data.first()
    var interval = 500.milliseconds

    while (left > Duration.ZERO) {
      if (playStateManager.playState != Playing) {
        if (!suspendUntilPlaying()) {
          // Left paused for so long that the listener is done. Still running, the timer would cut off
          // whatever they play next, maybe the next morning.
          Logger.i("Paused for $STALE_PAUSE_TIMEOUT, turning the sleep timer off")
          disable()
          return
        }
        // pressing play again is as clear a sign of being awake as it gets
        awake = true
      }
      if (awake) {
        awake = false
        if (sleepTimerPreferenceStore.data.first().restartWhenAwake && left < duration) {
          Logger.i("Listener is awake, restarting the sleep timer")
          left = duration
          interval = 500.milliseconds
          state.value = SleepTimerState.Enabled.WithDuration(left)
          playerController.setVolume(1F)
          rewindRecorder.onCountdownStarted()
          continue
        }
      }
      if (left < fadeOutDuration) {
        interval = 200.milliseconds
        updateVolume(left, fadeOutDuration)
      }
      delay(interval)
      left = max((left - interval).inWholeMilliseconds, 0).milliseconds
      state.value = SleepTimerState.Enabled.WithDuration(left)
    }
    playerController.setVolume(1f)
    state.value = SleepTimerState.Disabled

    playerController.record(ListeningEvent.Type.SleepTimerEnded, ListeningEvent.Source.SleepTimer)
    playerController.pauseWithRewind(fadeOutDuration)
    rewindRecorder.onCountdownFinished(timerDuration = duration)

    val shakeDetected = detectShakeWithTimeout()
    playerController.setVolume(1F)
    if (shakeDetected) {
      Logger.i("Shake detected, resetting timer")
      playerController.record(ListeningEvent.Type.SleepTimerExtended, ListeningEvent.Source.SleepTimer)
      playerController.play(source = null)
      startCountdown(duration)
    }
  }

  private suspend fun detectShakeWithTimeout(): Boolean {
    return withTimeoutOrNull(SHAKE_TO_RESET_TIME) {
      shakeDetector.detect()
      true
    } ?: false
  }

  private fun updateVolume(
    left: Duration,
    fadeOutDuration: Duration,
  ) {
    val percentage = (left / fadeOutDuration).toFloat().coerceIn(0f, 1f)
    val volume = 1 - FastOutSlowInInterpolator().getInterpolation(1 - percentage)
    playerController.setVolume(volume)
  }

  /**
   * An end of chapter timer has no countdown that runs out, so without this it would stay on when the listener stops
   * before the chapter ends, and stop the next listening session after a chapter.
   */
  private suspend fun disableWhenPausedForGood() {
    while (true) {
      playStateManager.playStateFlow.first { it != Playing }
      if (!suspendUntilPlaying()) {
        Logger.i("Paused for $STALE_PAUSE_TIMEOUT, turning the end of chapter sleep timer off")
        disable()
        return
      }
    }
  }

  /**
   * @return false if playback didn't continue within [STALE_PAUSE_TIMEOUT]
   */
  private suspend fun suspendUntilPlaying(): Boolean {
    if (playStateManager.playState == Playing) return true
    return withTimeoutOrNull(STALE_PAUSE_TIMEOUT) {
      playStateManager.playStateFlow.first { it == Playing }
    } != null
  }

  internal companion object {
    val SHAKE_TO_RESET_TIME = 30.seconds

    /** Long enough that the doorbell or a phone call doesn't turn the timer off. */
    val STALE_PAUSE_TIMEOUT = 30.minutes
  }
}
