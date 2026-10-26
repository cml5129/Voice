package voice.core.sleeptimer

import kotlinx.serialization.Serializable
import voice.core.data.BookId
import kotlin.time.Duration

/**
 * Stored when a countdown ran out, so the next time the app opens it can offer to go back to where the listener
 * might have fallen asleep.
 */
@Serializable
data class SleepTimerRewind(
  val bookId: BookId,
  /** Where the countdown last started, in the whole book rather than the chapter. */
  val startPositionInBookMs: Long,
  /** How long the countdown ran. It only counts while playing. */
  val timerDuration: Duration,
  val playbackSpeed: Float,
)
