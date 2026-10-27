package voice.core.sleeptimer

import io.mockk.Runs
import io.mockk.coEvery
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
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.impl.MemoryDataStore
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ListeningCheckTest {

  private val testScope = TestScope(StandardTestDispatcher())
  private val preferenceStore = MemoryDataStore(
    SleepTimerPreference.Default.copy(autoSleepTimerEnabled = true, listeningCheckEnabled = true),
  )
  private val playStateManager = PlayStateManager()
  private val shakes = Channel<Unit>(capacity = Channel.UNLIMITED)
  private val shakeDetector = object : ShakeDetector {
    override suspend fun detect() {
      shakes.receive()
    }
  }
  private val sleepTimer = mockk<SleepTimer>(relaxUnitFun = true)
  private val bookId = BookId("book")
  private val chapter = ChapterId("chapter")
  private var positionMs = 1_000L
  private val playerController = mockk<PlayerController> {
    coEvery { livePlaybackState(any()) } answers {
      LivePlaybackState(bookId, chapter, positionMs, isPlaying = true, playbackSpeed = 1F)
    }
    every { setVolume(any()) } just Runs
    every { record(any(), any()) } just Runs
    every { setPosition(any(), any(), any(), any()) } just Runs
    every { pause() } answers {
      playStateManager.playState = PlayStateManager.PlayState.Paused
    }
  }

  private fun listeningCheck(time: LocalTime = LocalTime.of(23, 0)) = ListeningCheck(
    sleepTimerPreferenceStore = SleepTimerPreferenceStore(preferenceStore),
    playStateManager = playStateManager,
    playerController = playerController,
    shakeDetector = shakeDetector,
    sleepTimer = sleepTimer,
    clock = Clock.fixed(Instant.parse("2020-01-01T${time}:00Z"), ZoneId.of("UTC")),
    scope = testScope.backgroundScope,
  )

  @Test
  fun `a missed check goes back to where playback started and pauses`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()
    positionMs = 700_000L

    advanceTimeBy(10.minutes + 1.seconds)
    verify { playerController.setVolume(ListeningCheck.PROMPT_VOLUME) }
    verify(exactly = 0) { playerController.pause() }

    advanceTimeBy(1.minutes)
    verify {
      playerController.setPosition(1_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
      playerController.pause()
    }
  }

  @Test
  fun `a shake keeps playing and moves where it goes back to`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(10.minutes + 1.seconds)
    positionMs = 600_000L
    shakes.trySend(Unit)
    runCurrent()
    verify { playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer) }
    verify { playerController.setVolume(1F) }
    verify { sleepTimer.onListenerAwake() }

    advanceTimeBy(1.minutes)
    verify(exactly = 0) { playerController.pause() }

    advanceTimeBy(10.minutes + 1.seconds)
    verify {
      playerController.setPosition(600_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
      playerController.pause()
    }
  }

  @Test
  fun `does not check when turned off`() = testScope.runTest {
    preferenceStore.updateData { it.copy(listeningCheckEnabled = false) }
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing

    advanceTimeBy(30.minutes)
    verify(exactly = 0) { playerController.setVolume(any()) }
    verify(exactly = 0) { playerController.pause() }
  }

  @Test
  fun `does not check outside of the sleep timer hours`() = testScope.runTest {
    listeningCheck(time = LocalTime.of(14, 0)).onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing

    advanceTimeBy(30.minutes)
    verify(exactly = 0) { playerController.setVolume(any()) }
    verify(exactly = 0) { playerController.pause() }
  }
}
