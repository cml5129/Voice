package voice.core.sleeptimer

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import voice.core.data.BookId
import voice.core.playback.CurrentBookResolver
import kotlin.time.Duration

/**
 * Remembers where the countdown last started and, once it ran out, stores a [SleepTimerRewind] so the listener is
 * offered to go back to where they might have fallen asleep. A countdown that restarted because the listener was
 * awake starts over from there.
 */
@SingleIn(AppScope::class)
@Inject
class SleepTimerRewindRecorder(
  private val sleepTimerRewindStore: SleepTimerRewindStore,
  private val currentBookResolver: CurrentBookResolver,
) {

  private var countdownStart: CountdownStart? = null

  suspend fun onCountdownStarted() {
    countdownStart = currentBookResolver.currentBook()?.let { book ->
      CountdownStart(
        bookId = book.id,
        positionInBookMs = book.position,
        playbackSpeed = book.content.playbackSpeed,
      )
    }
  }

  suspend fun onCountdownFinished(timerDuration: Duration) {
    val start = countdownStart ?: return
    countdownStart = null
    sleepTimerRewindStore.updateData {
      SleepTimerRewind(
        bookId = start.bookId,
        startPositionInBookMs = start.positionInBookMs,
        timerDuration = timerDuration,
        playbackSpeed = start.playbackSpeed,
      )
    }
  }

  private data class CountdownStart(
    val bookId: BookId,
    val positionInBookMs: Long,
    val playbackSpeed: Float,
  )
}
