package voice.core.sleeptimer

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
 *
 * Being awake when it goes off happens too, so turning the volume up or pressing play within [TAKE_BACK_TIME] takes
 * back the going back: the book goes on from where it went off, as if they had answered.
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
  private val awakeSpots: AwakeSpots,
) : AppInitializer {

  /** Where the listener last showed they were awake, kept after playback stops. */
  private var lastAwake: PlaybackPosition? = null
    set(value) {
      field = value
      if (value != null) awakeSpots.add(value)
    }

  /** Already backed up when the listener lost track there, so backing up never goes before it. */
  private var lostTrackSpot: PlaybackPosition? = null

  /**
   * Where the book was when it last went off, before it went back, for [TAKE_BACK_TIME]. Turning the volume up or
   * pressing play by then goes on from there. Later it can be half asleep, so then it stays where it went back to.
   */
  private var wentOff: PlaybackPosition? = null
  private var wentOffJob: Job? = null

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
    scope.launch {
      historyRecorder.events
        .filter { it.type == ListeningEvent.Type.Pause && it.source == ListeningEvent.Source.Unplugged }
        .collect { onUnplugged(PlaybackPosition(it.bookId, it.chapterId, it.time)) }
    }
    scope.launch {
      volumeChanges.changes.collect { onVolumeChangedWhilePaused() }
    }
  }

  /**
   * Turning the volume up while it's paused right after it went off, like with a sleep mask's button, says they're
   * awake, so it plays on from where it went off. Playing starts [checkWhilePlaying], which goes there.
   */
  private fun onVolumeChangedWhilePaused() {
    if (playStateManager.playState == Playing) return
    if (wentOff == null) return
    Logger.i("Volume changed right after going off, playing on from there")
    playerController.play(source = ListeningEvent.Source.SleepTimer)
  }

  private suspend fun checkWhilePlaying() {
    val playing = playingPosition() ?: return
    var lostTrackAt = lostTrack.tonight(playing.bookId)
    lostTrackSpot = lostTrackAt?.position
    var awake = lostTrackAt?.position ?: playing
    val undo = wentOff?.takeIf { it.bookId == playing.bookId }
    if (undo != null) {
      takeBackGoingBack(undo)
      lostTrackAt = null
      lostTrackSpot = null
      awake = undo
    }
    lastAwake = awake
    while (true) {
      // skipping, seeking or a volume button shows they're awake right there, so the next check waits for the full
      // interval again
      val action = withTimeoutOrNull(CHECK_INTERVAL) {
        merge(listenerActions().map { Action.Navigated(it.atMillis) }, volumeChanges.changes.map { Action.ChangedVolume }).first()
      }
      if (action != null) {
        val now = playerController.livePlaybackState()?.position() ?: return
        if (lostTrackAt == null || action == Action.ChangedVolume) {
          if (lostTrackAt != null) {
            // volume presses aren't in the history, so without this the next play would go back to where they lost
            // track before
            playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer)
          }
          lostTrackAt = null
          awake = now
        } else if (action is Action.Navigated) {
          lostTrackAt = lostTrack.moved(lostTrackAt, now, action.atMillis)
          awake = lostTrackAt.position
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
        fellAsleep(awake, canTakeBack = true)
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
    fellAsleep(awake, canTakeBack = true)
  }

  /**
   * Earbuds coming out or running flat while asleep pause the book where nobody heard it, and playing again would go
   * on from there. When it played on a while since they last were awake, it goes back as after a missed check.
   */
  private suspend fun onUnplugged(at: PlaybackPosition) {
    val awake = lastAwake ?: return
    if (awake.bookId != at.bookId || !shouldCheck()) return
    if (!lostTrack.isBefore(awake.copy(time = awake.time + UNPLUGGED_ASLEEP_AFTER.inWholeMilliseconds), at)) return
    Logger.i("Unplugged a while after the listener was last awake, going back to there")
    fellAsleep(awake)
  }

  private suspend fun answered(): Boolean = coroutineScope {
    val cue = launch { pulseVolume() }
    try {
      withTimeoutOrNull(ANSWER_TIME) {
        merge(flow { emit(shakeDetector.detect()) }, listenerActions().map {}, volumeChanges.changes).first()
      } != null
    } finally {
      cue.cancel()
      playerController.setVolume(1F)
    }
  }

  /**
   * A steady quieter voice is easy to miss half asleep, as the ear gets used to it. So it drops low and keeps
   * swelling back up for a moment, which stands out until it's answered.
   */
  private suspend fun pulseVolume() {
    while (true) {
      playerController.setVolume(PROMPT_VOLUME)
      delay(PROMPT_LOW_TIME)
      playerController.setVolume(PROMPT_SWELL_VOLUME)
      delay(PROMPT_SWELL_TIME)
    }
  }

  /**
   * @param canTakeBack whether the listener can take back the going back for a little while, see [wentOff]. Not after
   * earbuds came out, where turning the volume up would play on the phone's speaker.
   */
  private suspend fun fellAsleep(
    awake: PlaybackPosition,
    canTakeBack: Boolean = false,
  ) {
    val live = playerController.livePlaybackState()?.position()
    rememberWentOff(live?.takeIf { canTakeBack && it.bookId == awake.bookId })
    if (live?.bookId == awake.bookId) {
      // the last thing they remember can be right before that, so going back a bit more catches it, but not before
      // where they lost track, which already was backed up
      val backedUp = awake.copy(time = (awake.time - BACK_UP.inWholeMilliseconds).coerceAtLeast(0))
      val floor = lostTrackSpot
      val to = if (floor != null && lostTrack.isBefore(backedUp, floor) && !lostTrack.isBefore(awake, floor)) floor else backedUp
      playerController.setPosition(
        time = to.time,
        id = to.chapterId,
        type = ListeningEvent.Type.FellAsleep,
        source = ListeningEvent.Source.SleepTimer,
      )
    }
    playerController.pause()
    morningCheck.schedule(awake.bookId)
  }

  private fun rememberWentOff(at: PlaybackPosition?) {
    wentOffJob?.cancel()
    wentOff = at
    wentOffJob = at?.let {
      scope.launch {
        delay(TAKE_BACK_TIME)
        wentOff = null
      }
    }
  }

  /**
   * Goes on from where the book went off instead of where it went back to, and counts as an answer, so the next
   * check, or the sleep timer, starts over from there.
   */
  private fun takeBackGoingBack(went: PlaybackPosition) {
    Logger.i("Awake right after going off, going on from there")
    rememberWentOff(null)
    playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer)
    playerController.setPosition(
      time = went.time,
      id = went.chapterId,
      source = ListeningEvent.Source.SleepTimer,
    )
    sleepTimer.onListenerAwake()
  }

  /**
   * When a headset button starts the app, playback can start before the player knows its book, and the whole
   * session would go without checks.
   */
  private suspend fun playingPosition(): PlaybackPosition? = withTimeoutOrNull(PLAYER_READY_TIME) {
    var position = playerController.livePlaybackState()?.position()
    while (position == null) {
      delay(1.seconds)
      position = playerController.livePlaybackState()?.position()
    }
    position
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

  private sealed interface Action {
    data class Navigated(val atMillis: Long) : Action
    data object ChangedVolume : Action
  }

  private fun LivePlaybackState.position() = PlaybackPosition(bookId, chapterId, positionMs)

  internal companion object {
    val CHECK_INTERVAL = 10.minutes
    val ANSWER_TIME = 1.minutes
    const val PROMPT_VOLUME = 0.15F
    const val PROMPT_SWELL_VOLUME = 0.6F
    val PROMPT_LOW_TIME = 2.5.seconds
    val PROMPT_SWELL_TIME = 0.75.seconds

    /** A bit longer than the sleep timer listens for a shake after it ran out. */
    val KEEP_LISTENING_TIME = 45.seconds

    /** What they last remember can be a minute or more before they last showed they were awake. */
    val BACK_UP = 90.seconds

    /**
     * How long after going off pressing play or turning the volume up goes on from where it went off. In Nov 2026's
     * history, plays this soon after going off were the listener awake, who then had to find their place again.
     */
    val TAKE_BACK_TIME = 5.minutes

    /** How far the book played past where they were last awake before earbuds coming out counts as asleep. */
    val UNPLUGGED_ASLEEP_AFTER = 5.minutes

    val PLAYER_READY_TIME = 30.seconds
  }
}
