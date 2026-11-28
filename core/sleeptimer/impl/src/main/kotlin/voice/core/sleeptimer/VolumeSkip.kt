package voice.core.sleeptimer

import android.app.Application
import android.content.Context
import android.media.AudioManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.ListeningEvent
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import voice.core.playback.PlayerController
import voice.core.playback.VolumeSkipStore
import voice.core.playback.history.PlaybackPosition
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState.Playing
import java.time.Clock
import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pressing volume down twice in a row while playing goes back, volume up twice goes forward. During the listening
 * check's hours it goes to the last place the listener was known to be awake in that direction, see [AwakeSpots],
 * otherwise a minute. The volume then goes back to where it was before the first press.
 *
 * There's no way to see presses that don't change the volume, so at full volume going forward doesn't work, and at
 * no volume going back doesn't.
 */
@ContributesIntoSet(AppScope::class)
class VolumeSkip(
  private val context: Context,
  private val store: VolumeSkipStore,
  private val sleepTimerPreferenceStore: SleepTimerPreferenceStore,
  private val volumeChanges: VolumeChanges,
  private val playStateManager: PlayStateManager,
  private val playerController: PlayerController,
  private val awakeSpots: AwakeSpots,
  private val lostTrack: LostTrack,
  private val clock: Clock,
  private val scope: CoroutineScope,
) : AppInitializer {

  private var presses = emptyList<Press>()
  private var restoring: VolumeStep? = null

  override fun onAppStart(application: Application) {
    scope.launch {
      // a new press cancels the skip that waits to see whether a button is held or pressed a third time
      volumeChanges.steps.collectLatest { onStep(it) }
    }
  }

  private suspend fun onStep(step: VolumeStep) {
    val restored = restoring
    if (restored != null && step.to == restored.to && step.atMillis - restored.atMillis <= RESTORE_TIME.inWholeMilliseconds) {
      restoring = null
      return
    }
    val direction = (step.to - step.from).sign
    if (step.from < 0 || step.to < 0 || direction == 0) {
      presses = emptyList()
      return
    }
    val press = Press(step, direction, playing = playStateManager.playState == Playing)
    val last = presses.lastOrNull()
    presses = if (last != null && last.direction == direction && step.atMillis - last.step.atMillis <= PRESS_GAP.inWholeMilliseconds) {
      presses + press
    } else {
      listOf(press)
    }
    if (presses.size != 2 || !presses.all { it.playing }) return
    if (!store.data.first()) return
    delay(HOLD_TIME)
    val first = presses.first()
    presses = emptyList()
    restoreVolume(first.step.from, atMillis = step.atMillis + HOLD_TIME.inWholeMilliseconds)
    // the first press itself showed they're awake right there, which isn't a place to go to
    skip(forward = direction > 0, awakeBefore = first.step.atMillis)
  }

  private fun restoreVolume(
    volume: Int,
    atMillis: Long,
  ) {
    restoring = VolumeStep(from = -1, to = volume, atMillis = atMillis)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
  }

  private suspend fun skip(
    forward: Boolean,
    awakeBefore: Long,
  ) {
    val live = playerController.livePlaybackState() ?: return
    val now = PlaybackPosition(live.bookId, live.chapterId, live.positionMs)
    val awake = if (inSleepHours()) awakeSpot(now, forward, awakeBefore) else null
    val skipMs = SKIP.inWholeMilliseconds
    val to = awake ?: now.copy(time = (if (forward) now.time + skipMs else now.time - skipMs).coerceAtLeast(0))
    Logger.i("Volume skip ${if (forward) "forward" else "back"}, to where they were awake: ${awake != null}")
    playerController.setPosition(
      time = to.time,
      id = to.chapterId,
      source = ListeningEvent.Source.Headset,
    )
  }

  private suspend fun awakeSpot(
    now: PlaybackPosition,
    forward: Boolean,
    awakeBefore: Long,
  ): PlaybackPosition? = awakeSpots.before(awakeBefore).firstOrNull { spot ->
    spot.bookId == now.bookId &&
      (spot.chapterId != now.chapterId || abs(spot.time - now.time) >= MIN_JUMP.inWholeMilliseconds) &&
      if (forward) lostTrack.isBefore(now, spot) else lostTrack.isBefore(spot, now)
  }

  private suspend fun inSleepHours(): Boolean {
    val preference = sleepTimerPreferenceStore.data.first()
    return preference.autoSleepTimerEnabled &&
      preference.listeningCheckEnabled &&
      isTimeInRange(
        currentTime = clock.instant().atZone(clock.zone).toLocalTime(),
        startTime = preference.autoSleepStartTime,
        endTime = preference.autoSleepEndTime,
      )
  }

  private data class Press(
    val step: VolumeStep,
    val direction: Int,
    val playing: Boolean,
  )

  internal companion object {
    val PRESS_GAP = 600.milliseconds

    /** Holding a button repeats its press about every 50 ms, which comes well within this. */
    val HOLD_TIME = 300.milliseconds

    val RESTORE_TIME = 1.seconds
    val SKIP = 1.minutes

    /** A place they were awake closer than this is barely a jump, so the one before it is gone to instead. */
    val MIN_JUMP = 15.seconds
  }
}
