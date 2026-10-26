package voice.core.sleeptimer.impl

import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import voice.core.common.DispatcherProvider
import voice.core.data.ListeningEvent
import voice.core.playback.PlayerController
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.FadeOutStore
import voice.core.sleeptimer.ShakeDetector
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerImpl
import voice.core.sleeptimer.SleepTimerMode
import voice.core.sleeptimer.SleepTimerPreference
import voice.core.sleeptimer.SleepTimerPreferenceStore
import voice.core.sleeptimer.SleepTimerRewindRecorder
import voice.core.sleeptimer.SleepTimerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private class TestShakeDetector : ShakeDetector {
  private val shakes = Channel<Unit>(capacity = Channel.UNLIMITED)
  override suspend fun detect() {
    shakes.receive()
  }

  fun shake() {
    shakes.trySend(Unit)
  }
}

class SleepTimerImplTest {

  private val playStateManager = PlayStateManager().apply {
    playState = PlayStateManager.PlayState.Playing
  }
  private val shakeDetector = TestShakeDetector()
  private val sleepTimerPreferenceStore = MemoryDataStore(SleepTimerPreference.Default)
  private val setVolumeSlots = mutableListOf<Float>()
  private val playerController = mockk<PlayerController> {
    every { setVolume(capture(setVolumeSlots)) } just Runs
    every { pauseWithRewind(any()) } answers {
      playStateManager.playState = PlayStateManager.PlayState.Paused
    }
    every { record(any(), any()) } just Runs
    every {
      play(any())
    } answers {
      playStateManager.playState = PlayStateManager.PlayState.Playing
    }
  }

  private val fadeOutStore = MemoryDataStore(2.seconds)
  private val testDispatcher = StandardTestDispatcher()
  private val testScope = TestScope(testDispatcher)

  private val rewindRecorder = mockk<SleepTimerRewindRecorder>(relaxed = true)

  private val sleepTimer: SleepTimer

  init {
    val dispatcherProvider = DispatcherProvider(testDispatcher, testDispatcher, testDispatcher)
    sleepTimer = SleepTimerImpl(
      playStateManager,
      shakeDetector,
      SleepTimerPreferenceStore(sleepTimerPreferenceStore),
      playerController,
      FadeOutStore(fadeOutStore),
      dispatcherProvider,
      tracker = mockk(relaxed = true),
      rewindRecorder = rewindRecorder,
    )
  }

  @Test
  fun `initial state is disabled`() {
    assertEquals(expected = SleepTimerState.Disabled, actual = sleepTimer.state.value)
  }

  @Test
  fun `enable with fixed duration eventually disables and pauses playback`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(1.seconds))

    advanceTimeBy(2.seconds)
    assertEquals(expected = SleepTimerState.Disabled, actual = sleepTimer.state.value)
    coVerify(exactly = 1) { playerController.pauseWithRewind(any()) }
  }

  @Test
  fun `enable with EndOfChapter sets state`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.EndOfChapter)

    advanceTimeBy(1)
    assertEquals(expected = SleepTimerState.Enabled.WithEndOfChapter, actual = sleepTimer.state.value)
  }

  @Test
  fun `turning the timer on goes into the history`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.EndOfChapter)
    sleepTimer.enable(SleepTimerMode.TimedWithDefault)

    verify {
      playerController.record(ListeningEvent.Type.SleepTimerStarted, ListeningEvent.Source.App)
      playerController.record(ListeningEvent.Type.SleepTimerStarted, ListeningEvent.Source.SleepTimer)
    }
  }

  @Test
  fun `disable cancels timer and resets state`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(5.seconds))
    advanceTimeBy(1.seconds)

    sleepTimer.disable()

    assertEquals(expected = SleepTimerState.Disabled, actual = sleepTimer.state.value)
  }

  @Test
  fun withDurationResetsVolume() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(5.seconds))
    advanceTimeBy(3.seconds)
    yield()

    assertEquals(expected = setOf(1F), actual = setVolumeSlots.toSet())

    setVolumeSlots.clear()
    advanceTimeBy(1.seconds)
    yield()
    assertTrue(setVolumeSlots.isNotEmpty())
    assertTrue(setVolumeSlots.zipWithNext().all { (previous, next) -> previous > next })

    setVolumeSlots.clear()
    advanceTimeBy(2.seconds)
    yield()
    assertEquals(expected = 1f, actual = setVolumeSlots.last())
  }

  @Test
  fun shake_does_not_cancel_second_countdown_after_window() = testScope.runTest {
    // Use a LONG duration so we can observe behavior across the 30s window
    val longDuration = SleepTimerImpl.SHAKE_TO_RESET_TIME * 2

    sleepTimer.enable(SleepTimerMode.TimedWithDuration(longDuration))

    // 1) Let the first countdown finish and enter the shake window
    advanceTimeBy(longDuration + 1.seconds)
    runCurrent()
    coVerify(exactly = 1) { playerController.pauseWithRewind(any()) }
    assertEquals(expected = SleepTimerState.Disabled, actual = sleepTimer.state.value)

    // 2) Trigger the shake → a new countdown should start independently of the old timeout
    shakeDetector.shake()
    runCurrent()
    verify(exactly = 1) { playerController.play(source = null) }
    assertEquals(expected = SleepTimerState.Enabled.WithDuration(longDuration), actual = sleepTimer.state.value)

    // 3) Advance past the original 30s shake window and allow the second countdown to finish
    advanceTimeBy(SleepTimerImpl.SHAKE_TO_RESET_TIME + longDuration + 2.seconds)
    runCurrent()

    coVerify(exactly = 2) { playerController.pauseWithRewind(any()) }
    assertEquals(expected = SleepTimerState.Disabled, actual = sleepTimer.state.value)
  }

  @Test
  fun `the listener being awake restarts the countdown`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(10.seconds))
    advanceTimeBy(5.seconds)
    assertLeftAtMost(5.seconds)

    sleepTimer.onListenerAwake()
    advanceTimeBy(600.milliseconds)

    assertLeftAtLeast(9.seconds)
    coVerify(exactly = 2) { rewindRecorder.onCountdownStarted() }
  }

  @Test
  fun `the listener being awake keeps the countdown when restarting is turned off`() = testScope.runTest {
    sleepTimerPreferenceStore.updateData { it.copy(restartWhenAwake = false) }
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(10.seconds))
    advanceTimeBy(5.seconds)

    sleepTimer.onListenerAwake()
    advanceTimeBy(600.milliseconds)

    assertLeftAtMost(5.seconds)
  }

  @Test
  fun `resuming after a pause restarts the countdown`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(10.seconds))
    advanceTimeBy(5.seconds)

    playStateManager.playState = PlayStateManager.PlayState.Paused
    advanceTimeBy(1.minutes)
    assertLeftAtMost(5.seconds)

    playStateManager.playState = PlayStateManager.PlayState.Playing
    advanceTimeBy(100.milliseconds)
    assertLeftAtLeast(9.seconds)
  }

  @Test
  fun `a long pause turns the timer off`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(10.seconds))
    advanceTimeBy(2.seconds)

    playStateManager.playState = PlayStateManager.PlayState.Paused
    advanceTimeBy(SleepTimerImpl.STALE_PAUSE_TIMEOUT + 1.seconds)

    assertEquals(expected = SleepTimerState.Disabled, actual = sleepTimer.state.value)
    coVerify(exactly = 0) { playerController.pauseWithRewind(any()) }
  }

  @Test
  fun `a long pause turns the end of chapter timer off`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.EndOfChapter)
    runCurrent()

    playStateManager.playState = PlayStateManager.PlayState.Paused
    advanceTimeBy(SleepTimerImpl.STALE_PAUSE_TIMEOUT - 1.minutes)
    assertEquals(expected = SleepTimerState.Enabled.WithEndOfChapter, actual = sleepTimer.state.value)

    advanceTimeBy(2.minutes)
    assertEquals(expected = SleepTimerState.Disabled, actual = sleepTimer.state.value)
  }

  @Test
  fun `finished countdown is recorded for rewinding`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(3.seconds))
    runCurrent()
    coVerify(exactly = 1) { rewindRecorder.onCountdownStarted() }
    coVerify(exactly = 0) { rewindRecorder.onCountdownFinished(any()) }

    advanceTimeBy(4.seconds)
    runCurrent()

    coVerify(exactly = 1) { rewindRecorder.onCountdownFinished(3.seconds) }
  }

  @Test
  fun `turned off countdown is not recorded for rewinding`() = testScope.runTest {
    sleepTimer.enable(SleepTimerMode.TimedWithDuration(3.seconds))
    advanceTimeBy(1.seconds)
    sleepTimer.disable()
    advanceTimeBy(5.seconds)

    coVerify(exactly = 0) { rewindRecorder.onCountdownFinished(any()) }
  }

  private fun assertLeftAtMost(duration: Duration) {
    val state = assertIs<SleepTimerState.Enabled.WithDuration>(sleepTimer.state.value)
    assertTrue(state.leftDuration <= duration, "${state.leftDuration} left, expected at most $duration")
  }

  private fun assertLeftAtLeast(duration: Duration) {
    val state = assertIs<SleepTimerState.Enabled.WithDuration>(sleepTimer.state.value)
    assertTrue(state.leftDuration >= duration, "${state.leftDuration} left, expected at least $duration")
  }
}
