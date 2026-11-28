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
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState.Playing
import voice.core.transcript.TranscriptCue
import voice.core.transcript.TranscriptRepository
import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pressing volume down twice in a row while playing goes back, volume up twice goes forward, by about a minute, to
 * where a sentence starts when the transcript has one, see [volumeSkipTarget]. The volume then goes back to where it
 * was before the first press.
 *
 * There's no way to see presses that don't change the volume, so at full volume going forward doesn't work, and at
 * no volume going back doesn't.
 */
@ContributesIntoSet(AppScope::class)
class VolumeSkip(
  private val context: Context,
  private val store: VolumeSkipStore,
  private val volumeChanges: VolumeChanges,
  private val playStateManager: PlayStateManager,
  private val playerController: PlayerController,
  private val transcripts: TranscriptRepository,
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
    skip(forward = direction > 0)
  }

  private fun restoreVolume(
    volume: Int,
    atMillis: Long,
  ) {
    restoring = VolumeStep(from = -1, to = volume, atMillis = atMillis)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
  }

  private suspend fun skip(forward: Boolean) {
    val live = playerController.livePlaybackState() ?: return
    val target = volumeSkipTarget(transcripts.cues(live.chapterId), live.positionMs, forward)
    Logger.i("Volume skip ${if (forward) "forward" else "back"} by ${abs(target - live.positionMs)} ms")
    playerController.setPosition(
      time = target,
      id = live.chapterId,
      source = ListeningEvent.Source.Headset,
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

    /** How far from [SKIP] a sentence may start to be gone to instead. */
    val SNAP = 20.seconds
  }
}

/**
 * About [VolumeSkip.SKIP] from [positionMs]. A sentence starting within [VolumeSkip.SNAP] of there is easier to pick up
 * from, and among those the one after the longest pause most likely starts a paragraph.
 */
internal fun volumeSkipTarget(
  cues: List<TranscriptCue>?,
  positionMs: Long,
  forward: Boolean,
): Long {
  val skipMs = VolumeSkip.SKIP.inWholeMilliseconds
  val plain = if (forward) positionMs + skipMs else positionMs - skipMs
  val snapMs = VolumeSkip.SNAP.inWholeMilliseconds
  val all = cues.orEmpty()
  val sentenceStart = all.withIndex()
    .filter { (index, cue) ->
      val start = cue.timestampStartMs
      abs(start - plain) <= snapMs &&
        (if (forward) start > positionMs else start < positionMs) &&
        (index == 0 || all[index - 1].endsSentence())
    }
    .maxByOrNull { (index, cue) ->
      if (index == 0) Long.MAX_VALUE else cue.timestampStartMs - all[index - 1].timestampEndMs
    }
    ?.value
    ?.timestampStartMs
  return (sentenceStart ?: plain).coerceAtLeast(0)
}

private fun TranscriptCue.endsSentence(): Boolean {
  val last = text.trimEnd { it.isWhitespace() || it in "\"'”’)]" }.lastOrNull()
  return last != null && last in ".?!…"
}
