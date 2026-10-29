package voice.core.sleeptimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.playback.history.PlaybackPosition
import kotlin.time.Duration.Companion.seconds

/**
 * Shows the [MorningCheck] when its time comes, and takes the answer from its buttons.
 */
class MorningCheckReceiver : BroadcastReceiver() {

  private val scope = MainScope()

  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    val bookId = intent.getStringExtra(EXTRA_BOOK_ID)?.let(::BookId) ?: return
    val morningCheck = rootGraphAs<MorningCheckGraph>().morningCheck
    val result = goAsync()
    scope.launch {
      try {
        withTimeout(20.seconds) {
          when (intent.action) {
            ACTION_ASK -> morningCheck.ask(bookId)
            ACTION_RIGHT, ACTION_WRONG -> {
              val chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID)?.let { ChapterId(it) } ?: return@withTimeout
              val spot = PlaybackPosition(bookId, chapterId, intent.getLongExtra(EXTRA_TIME, 0))
              morningCheck.onAnswer(spot, right = intent.action == ACTION_RIGHT)
            }
          }
        }
      } finally {
        result.finish()
      }
    }
  }

  internal companion object {
    const val ACTION_ASK = "voice.sleeptimer.morningCheck.ASK"
    const val ACTION_RIGHT = "voice.sleeptimer.morningCheck.RIGHT"
    const val ACTION_WRONG = "voice.sleeptimer.morningCheck.WRONG"
    const val EXTRA_BOOK_ID = "bookId"
    const val EXTRA_CHAPTER_ID = "chapterId"
    const val EXTRA_TIME = "time"
  }
}

@ContributesTo(AppScope::class)
interface MorningCheckGraph {
  val morningCheck: MorningCheck
}
