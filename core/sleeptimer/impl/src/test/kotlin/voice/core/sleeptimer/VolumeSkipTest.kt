package voice.core.sleeptimer

import android.content.Context
import android.media.AudioManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.VolumeSkipStore
import voice.core.playback.history.PlaybackPosition
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.impl.MemoryDataStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test

class VolumeSkipTest {

  private val testScope = TestScope(StandardTestDispatcher())
  private val enabled = MemoryDataStore(true)
  private val sleepPreference = MemoryDataStore(
    SleepTimerPreference.Default.copy(autoSleepTimerEnabled = true, listeningCheckEnabled = true),
  )
  private val clock = TestClock(Instant.parse("2020-01-01T23:00:00Z").toEpochMilli())
  private val playStateManager = PlayStateManager().apply { playState = PlayStateManager.PlayState.Playing }
  private val bookId = BookId("book")
  private val chapter = ChapterId("chapter")
  private var positionMs = 600_000L
  private val playerController = mockk<PlayerController> {
    coEvery { livePlaybackState(any()) } answers {
      LivePlaybackState(bookId, chapter, positionMs, isPlaying = true, playbackSpeed = 1F)
    }
    every { setPosition(any(), any(), any(), any()) } just Runs
  }
  private val audioManager = mockk<AudioManager> {
    every { setStreamVolume(any(), any(), any()) } just Runs
  }
  private val context = mockk<Context> {
    every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
  }
  private val awakeSpots = AwakeSpots(clock)
  private val volumeChanges = VolumeChanges()

  private fun start() {
    VolumeSkip(
      context = context,
      store = VolumeSkipStore(enabled),
      sleepTimerPreferenceStore = SleepTimerPreferenceStore(sleepPreference),
      volumeChanges = volumeChanges,
      playStateManager = playStateManager,
      playerController = playerController,
      awakeSpots = awakeSpots,
      lostTrack = LostTrack(mockk(relaxed = true), mockk(relaxed = true), clock),
      clock = clock,
      scope = testScope.backgroundScope,
    ).onAppStart(mockk())
    testScope.runCurrent()
  }

  private fun awakeAt(timeMs: Long) {
    awakeSpots.add(PlaybackPosition(bookId, chapter, timeMs))
    clock.millis += 1_000
  }

  private fun doublePress(
    from: Int,
    to: Int,
  ) {
    val step = to - from
    val at = clock.millis
    press(from = from, to = to, atMillis = at)
    // the listening check takes the first press as being awake right there
    awakeSpots.add(PlaybackPosition(bookId, chapter, positionMs))
    press(from = to, to = to + step, atMillis = at + 300)
    testScope.advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    testScope.runCurrent()
  }

  private fun press(
    from: Int,
    to: Int,
    atMillis: Long,
  ) {
    clock.millis = atMillis
    volumeChanges.onChanged(VolumeStep(from, to, atMillis))
    testScope.runCurrent()
  }

  @Test
  fun `two presses down in sleep hours go back to where they were last awake`() = testScope.runTest {
    awakeAt(300_000)
    awakeAt(450_000)
    start()
    doublePress(from = 8, to = 7)
    verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0) }
    verify { playerController.setPosition(450_000, chapter, any(), any()) }
  }

  @Test
  fun `two presses up go forward to where they were last awake ahead`() = testScope.runTest {
    awakeAt(700_000)
    awakeAt(500_000)
    start()
    doublePress(from = 5, to = 6)
    verify { playerController.setPosition(700_000, chapter, any(), any()) }
  }

  @Test
  fun `an awake spot right next to here is skipped for an earlier one`() = testScope.runTest {
    awakeAt(300_000)
    awakeAt(595_000)
    start()
    doublePress(from = 8, to = 7)
    verify { playerController.setPosition(300_000, chapter, any(), any()) }
  }

  @Test
  fun `without an awake spot that way it goes a minute`() = testScope.runTest {
    awakeAt(300_000)
    start()
    doublePress(from = 5, to = 6)
    verify { playerController.setPosition(660_000, chapter, any(), any()) }
  }

  @Test
  fun `outside sleep hours it goes a minute`() = testScope.runTest {
    sleepPreference.updateData { it.copy(listeningCheckEnabled = false) }
    awakeAt(300_000)
    start()
    doublePress(from = 8, to = 7)
    verify { playerController.setPosition(540_000, chapter, any(), any()) }
  }

  @Test
  fun `going back near the start stops at the start`() = testScope.runTest {
    sleepPreference.updateData { it.copy(listeningCheckEnabled = false) }
    positionMs = 20_000
    start()
    doublePress(from = 8, to = 7)
    verify { playerController.setPosition(0, chapter, any(), any()) }
  }

  @Test
  fun `putting the volume back is not a press`() = testScope.runTest {
    start()
    doublePress(from = 8, to = 7)
    press(from = 6, to = 8, atMillis = clock.millis + 350)
    press(from = 8, to = 9, atMillis = clock.millis + 250)
    testScope.advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    runCurrent()
    verify(exactly = 1) { playerController.setPosition(any(), any(), any(), any()) }
  }

  @Test
  fun `holding a button does not skip`() = testScope.runTest {
    start()
    press(from = 10, to = 9, atMillis = 0)
    press(from = 9, to = 8, atMillis = 500)
    press(from = 8, to = 7, atMillis = 550)
    press(from = 7, to = 6, atMillis = 600)
    advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    runCurrent()
    verify(exactly = 0) { playerController.setPosition(any(), any(), any(), any()) }
    verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
  }

  @Test
  fun `presses far apart do not skip`() = testScope.runTest {
    start()
    press(from = 8, to = 7, atMillis = 0)
    press(from = 7, to = 6, atMillis = 2_000)
    advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    runCurrent()
    verify(exactly = 0) { playerController.setPosition(any(), any(), any(), any()) }
  }

  @Test
  fun `does nothing when turned off`() = testScope.runTest {
    enabled.updateData { false }
    start()
    doublePress(from = 8, to = 7)
    verify(exactly = 0) { playerController.setPosition(any(), any(), any(), any()) }
  }

  @Test
  fun `a press that started playing is not part of a skip`() = testScope.runTest {
    playStateManager.playState = PlayStateManager.PlayState.Paused
    start()
    press(from = 5, to = 6, atMillis = 0)
    playStateManager.playState = PlayStateManager.PlayState.Playing
    press(from = 6, to = 7, atMillis = 300)
    advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    runCurrent()
    verify(exactly = 0) { playerController.setPosition(any(), any(), any(), any()) }
  }

  private class TestClock(var millis: Long) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    override fun millis(): Long = millis
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
  }
}
