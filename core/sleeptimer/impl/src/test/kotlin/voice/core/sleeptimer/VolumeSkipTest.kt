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
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.impl.MemoryDataStore
import voice.core.transcript.TranscriptCue
import voice.core.transcript.TranscriptRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeSkipTest {

  private val testScope = TestScope(StandardTestDispatcher())
  private val enabled = MemoryDataStore(true)
  private val playStateManager = PlayStateManager().apply { playState = PlayStateManager.PlayState.Playing }
  private val chapter = ChapterId("chapter")
  private val playerController = mockk<PlayerController> {
    coEvery { livePlaybackState(any()) } returns
      LivePlaybackState(BookId("book"), chapter, 600_000, isPlaying = true, playbackSpeed = 1F)
    every { setPosition(any(), any(), any(), any()) } just Runs
  }
  private val audioManager = mockk<AudioManager> {
    every { setStreamVolume(any(), any(), any()) } just Runs
  }
  private val context = mockk<Context> {
    every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
  }
  private val transcripts = mockk<TranscriptRepository> {
    coEvery { cues(any()) } returns null
  }
  private val volumeChanges = VolumeChanges()

  private fun start() {
    VolumeSkip(
      context = context,
      store = VolumeSkipStore(enabled),
      volumeChanges = volumeChanges,
      playStateManager = playStateManager,
      playerController = playerController,
      transcripts = transcripts,
      scope = testScope.backgroundScope,
    ).onAppStart(mockk())
    testScope.runCurrent()
  }

  private fun press(
    from: Int,
    to: Int,
    atMillis: Long,
  ) {
    volumeChanges.onChanged(VolumeStep(from, to, atMillis))
    testScope.runCurrent()
  }

  @Test
  fun `two presses down go back a minute and put the volume back`() = testScope.runTest {
    start()
    press(from = 8, to = 7, atMillis = 0)
    press(from = 7, to = 6, atMillis = 300)
    advanceTimeBy(VolumeSkip.HOLD_TIME + VolumeSkip.HOLD_TIME)
    runCurrent()
    verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0) }
    verify { playerController.setPosition(540_000, chapter, any(), any()) }
  }

  @Test
  fun `two presses up go forward`() = testScope.runTest {
    start()
    press(from = 5, to = 6, atMillis = 0)
    press(from = 6, to = 7, atMillis = 300)
    advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    runCurrent()
    verify { playerController.setPosition(660_000, chapter, any(), any()) }
  }

  @Test
  fun `putting the volume back is not a press`() = testScope.runTest {
    start()
    press(from = 8, to = 7, atMillis = 0)
    press(from = 7, to = 6, atMillis = 300)
    advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    runCurrent()
    press(from = 6, to = 8, atMillis = 650)
    press(from = 8, to = 9, atMillis = 900)
    advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
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
    press(from = 8, to = 7, atMillis = 0)
    press(from = 7, to = 6, atMillis = 300)
    advanceTimeBy(VolumeSkip.HOLD_TIME * 2)
    runCurrent()
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

  @Test
  fun `goes to the sentence after the longest pause near a minute back`() {
    val cues = listOf(
      cue(30_000, 44_000, "Not near enough."),
      cue(45_000, 49_000, "A sentence."),
      cue(50_000, 54_000, "Then another"),
      cue(54_100, 58_000, "half of one."),
      cue(61_000, 70_000, "After a long pause."),
      cue(71_000, 79_000, "Next one."),
    )
    assertEquals(61_000, volumeSkipTarget(cues, positionMs = 120_000, forward = false))
  }

  @Test
  fun `never starts mid sentence`() {
    val cues = listOf(
      cue(10_000, 39_000, "Earlier,"),
      cue(40_000, 55_000, "this sentence goes on"),
      cue(58_000, 70_000, "and on"),
    )
    assertEquals(60_000, volumeSkipTarget(cues, positionMs = 120_000, forward = false))
  }

  @Test
  fun `goes forward to a sentence start`() {
    val cues = listOf(
      cue(100_000, 165_000, "Before."),
      cue(170_000, 179_000, "“Said it.”"),
      cue(185_000, 190_000, "New paragraph."),
    )
    assertEquals(185_000, volumeSkipTarget(cues, positionMs = 120_000, forward = true))
  }

  @Test
  fun `going back near the start stops at the start`() {
    assertEquals(0, volumeSkipTarget(null, positionMs = 20_000, forward = false))
  }

  private fun cue(
    start: Long,
    end: Long,
    text: String,
  ) = TranscriptCue(start, end, text)
}
