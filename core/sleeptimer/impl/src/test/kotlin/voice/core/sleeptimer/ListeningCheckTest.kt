package voice.core.sleeptimer

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.history.ListeningHistoryRecorder
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.impl.MemoryDataStore
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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
  private val recordedEvents = MutableSharedFlow<ListeningEvent>(extraBufferCapacity = 16)
  private val historyRecorder = mockk<ListeningHistoryRecorder> {
    every { events } returns recordedEvents
  }
  private val bookId = BookId("book")

  /** Newest first, like the repo. */
  private var history = emptyList<ListeningEvent>()
  private val historyRepo = mockk<ListeningHistoryRepo> {
    every { events(any()) } returns flow { emit(history) }
  }
  private val now = Instant.parse("2020-01-01T23:00:00Z")
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

  private val morningCheck = mockk<MorningCheck>(relaxed = true)
  private val volumeChanges = VolumeChanges()

  private fun listeningCheck(time: LocalTime = LocalTime.of(23, 0)): ListeningCheck {
    val clock = Clock.fixed(Instant.parse("2020-01-01T$time:00Z"), ZoneId.of("UTC"))
    return ListeningCheck(
      sleepTimerPreferenceStore = SleepTimerPreferenceStore(preferenceStore),
      playStateManager = playStateManager,
      playerController = playerController,
      shakeDetector = shakeDetector,
      sleepTimer = sleepTimer,
      historyRecorder = historyRecorder,
      volumeChanges = volumeChanges,
      lostTrack = LostTrack(historyRepo, mockk<BookRepository>(relaxed = true), clock),
      morningCheck = morningCheck,
      clock = clock,
      scope = testScope.backgroundScope,
    )
  }

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
    coVerify { morningCheck.schedule(bookId) }
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
  fun `a volume button counts as an answer`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(10.minutes + 1.seconds)
    positionMs = 600_000L
    volumeChanges.onChanged()
    runCurrent()
    verify { playerController.record(ListeningEvent.Type.StillListening, ListeningEvent.Source.SleepTimer) }
    verify { playerController.setVolume(1F) }

    advanceTimeBy(1.minutes)
    verify(exactly = 0) { playerController.pause() }
  }

  @Test
  fun `a volume button after a missed check counts as awake`() = testScope.runTest {
    history = listOf(fellAsleep(ago = 30.minutes, from = 2_000_000L, to = 1_000_000L))
    positionMs = 1_500_000L
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(5.minutes)
    positionMs = 1_800_000L
    volumeChanges.onChanged()
    runCurrent()

    advanceTimeBy(11.minutes + 1.seconds)
    verify {
      playerController.setPosition(1_800_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
  }

  @Test
  fun `the sleep timer running out goes back to where they were last awake`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(5.minutes)
    positionMs = 300_000L
    volumeChanges.onChanged()
    runCurrent()

    advanceTimeBy(3.minutes)
    positionMs = 480_000L
    recordedEvents.emit(listenerEvent(ListeningEvent.Type.SleepTimerEnded, ListeningEvent.Source.SleepTimer))
    playStateManager.playState = PlayStateManager.PlayState.Paused
    runCurrent()
    verify(exactly = 0) { playerController.setPosition(any(), any(), any(), any()) }

    advanceTimeBy(ListeningCheck.KEEP_LISTENING_TIME + 1.seconds)
    verify {
      playerController.setPosition(300_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
    coVerify { morningCheck.schedule(bookId) }
  }

  @Test
  fun `a shake right after the sleep timer ran out keeps the place`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(8.minutes)
    positionMs = 480_000L
    recordedEvents.emit(listenerEvent(ListeningEvent.Type.SleepTimerEnded, ListeningEvent.Source.SleepTimer))
    playStateManager.playState = PlayStateManager.PlayState.Paused
    runCurrent()
    advanceTimeBy(10.seconds)
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(ListeningCheck.KEEP_LISTENING_TIME)
    verify(exactly = 0) { playerController.setPosition(any(), any(), any(), any()) }
  }

  @Test
  fun `a skip moves where it goes back to and waits a full interval`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(5.minutes)
    positionMs = 2_100_000L
    recordedEvents.emit(listenerEvent(ListeningEvent.Type.SkipForward))
    runCurrent()

    advanceTimeBy(9.minutes)
    verify(exactly = 0) { playerController.setVolume(ListeningCheck.PROMPT_VOLUME) }

    advanceTimeBy(2.minutes + 1.seconds)
    verify {
      playerController.setPosition(2_100_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
      playerController.pause()
    }
  }

  @Test
  fun `a skip during a check counts as an answer`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(10.minutes + 1.seconds)
    positionMs = 2_100_000L
    recordedEvents.emit(listenerEvent(ListeningEvent.Type.SkipForward))
    runCurrent()
    verify { playerController.setVolume(1F) }

    advanceTimeBy(1.minutes)
    verify(exactly = 0) { playerController.pause() }

    advanceTimeBy(10.minutes + 1.seconds)
    verify {
      playerController.setPosition(2_100_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
  }

  @Test
  fun `a position synced from another device does not count as awake`() = testScope.runTest {
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(5.minutes)
    positionMs = 2_100_000L
    recordedEvents.emit(listenerEvent(ListeningEvent.Type.Seek, ListeningEvent.Source.OtherDevice))
    runCurrent()

    advanceTimeBy(6.minutes + 1.seconds)
    verify {
      playerController.setPosition(1_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
  }

  @Test
  fun `playing again after a missed check goes back to where they lost track`() = testScope.runTest {
    history = listOf(fellAsleep(ago = 30.minutes, from = 2_000_000L, to = 1_000_000L))
    positionMs = 1_500_000L
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(11.minutes + 1.seconds)
    verify {
      playerController.setPosition(1_000_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
      playerController.pause()
    }
  }

  @Test
  fun `skipping ahead after a missed check does not count as awake`() = testScope.runTest {
    history = listOf(fellAsleep(ago = 30.minutes, from = 2_000_000L, to = 1_000_000L))
    positionMs = 1_500_000L
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(5.minutes)
    positionMs = 2_500_000L
    recordedEvents.emit(listenerEvent(ListeningEvent.Type.SkipForward))
    runCurrent()

    advanceTimeBy(11.minutes + 1.seconds)
    verify {
      playerController.setPosition(1_000_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
  }

  @Test
  fun `going back on their own after a missed check is kept for the rest of the night`() = testScope.runTest {
    history = listOf(
      fellAsleep(ago = 1.hours, from = 2_600_000L, to = 2_000_000L),
      listenerEvent(ListeningEvent.Type.JumpBack, to = 2_600_000L, ago = 2.hours),
      fellAsleep(ago = 2.hours, from = 2_600_000L, to = 2_000_000L),
      listenerEvent(ListeningEvent.Type.Seek, to = 900_000L, ago = 3.hours),
      fellAsleep(ago = 4.hours, from = 2_000_000L, to = 1_000_000L),
    )
    positionMs = 2_100_000L
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(11.minutes + 1.seconds)
    verify {
      playerController.setPosition(900_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
  }

  @Test
  fun `answering a check after a missed one counts as awake again`() = testScope.runTest {
    history = listOf(fellAsleep(ago = 30.minutes, from = 2_000_000L, to = 1_000_000L))
    positionMs = 1_500_000L
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(10.minutes + 1.seconds)
    positionMs = 1_600_000L
    shakes.trySend(Unit)
    runCurrent()
    verify(exactly = 0) { playerController.pause() }

    advanceTimeBy(11.minutes + 1.seconds)
    verify {
      playerController.setPosition(1_600_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
  }

  @Test
  fun `a check missed the night before does not count`() = testScope.runTest {
    history = listOf(fellAsleep(ago = 13.hours, from = 2_000_000L, to = 1_000_000L))
    positionMs = 1_500_000L
    listeningCheck().onAppStart(mockk())
    playStateManager.playState = PlayStateManager.PlayState.Playing
    runCurrent()

    advanceTimeBy(11.minutes + 1.seconds)
    verify {
      playerController.setPosition(1_500_000L, chapter, ListeningEvent.Type.FellAsleep, ListeningEvent.Source.SleepTimer)
    }
  }

  private fun fellAsleep(
    ago: Duration,
    from: Long,
    to: Long,
  ) = ListeningEvent(
    bookId = bookId,
    type = ListeningEvent.Type.FellAsleep,
    source = ListeningEvent.Source.SleepTimer,
    atMillis = now.toEpochMilli() - ago.inWholeMilliseconds,
    chapterId = chapter,
    time = from,
    toChapterId = chapter,
    toTime = to,
  )

  private fun listenerEvent(
    type: ListeningEvent.Type,
    source: ListeningEvent.Source = ListeningEvent.Source.App,
    to: Long? = null,
    ago: Duration = Duration.ZERO,
  ) = ListeningEvent(
    bookId = bookId,
    type = type,
    source = source,
    atMillis = now.toEpochMilli() - ago.inWholeMilliseconds,
    chapterId = chapter,
    time = positionMs,
    toChapterId = to?.let { chapter },
    toTime = to,
  )

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
