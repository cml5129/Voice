package voice.core.sleeptimer

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import voice.core.data.BookId
import voice.core.data.ListeningEvent
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.playback.history.PlaybackPosition
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Where the listener lost track of a book. A missed listening check makes the book go back to where they
 * were last awake, and they lost track from there on. Pressing play again in the night, or skipping
 * ahead, can happen half asleep, so until they answer a check, it stays the earliest spot the book went
 * back to, or where they went back to on their own.
 *
 * Going forward again right after going back is fixing a slip, like tapping the wrong chapter, so it undoes
 * that going back, but never goes past where they lost track before it.
 */
@Inject
class LostTrack(
  private val historyRepo: ListeningHistoryRepo,
  private val bookRepository: BookRepository,
  private val clock: Clock,
) {

  suspend fun spot(
    bookId: BookId,
    within: Duration = TONIGHT,
  ): PlaybackPosition? = tonight(bookId, within)?.position

  /** Like [spot], with what's needed to follow it further. */
  suspend fun tonight(
    bookId: BookId,
    within: Duration = TONIGHT,
  ): Spot? {
    val since = clock.millis() - within.inWholeMilliseconds
    val events = historyRepo.events(bookId).first()
      .takeWhile { it.atMillis >= since }
      .asReversed()
    var spot: Spot? = null
    for (event in events) {
      val to = PlaybackPosition(bookId, event.toChapterId ?: event.chapterId, event.toTime ?: event.time)
      spot = when {
        event.type == ListeningEvent.Type.StillListening -> null
        event.type == ListeningEvent.Type.FellAsleep -> spot?.takeUnless { isBefore(to, it.position) } ?: Spot(to)
        // playing again starts a little before where it paused, which isn't going back
        spot != null && event.isListenerAction() && event.type != ListeningEvent.Type.Play -> moved(spot, to, event.atMillis)
        else -> spot
      }
    }
    return spot
  }

  /**
   * Where they lost track after they moved to [to] on their own at [atMillis]: going back lowers it, going
   * forward only undoes a going back from just before.
   */
  suspend fun moved(
    spot: Spot,
    to: PlaybackPosition,
    atMillis: Long,
  ): Spot {
    val undoTo = spot.beforeGoingBack?.takeIf { atMillis - spot.wentBackAtMillis <= SLIP_TIME.inWholeMilliseconds }
    return when {
      isBefore(to, spot.position) -> Spot(to, beforeGoingBack = undoTo ?: spot.position, wentBackAtMillis = atMillis)
      undoTo == null -> spot.copy(beforeGoingBack = null)
      isBefore(to, undoTo) -> spot.copy(position = to)
      else -> Spot(undoTo)
    }
  }

  suspend fun isBefore(
    a: PlaybackPosition,
    b: PlaybackPosition,
  ): Boolean {
    if (a.chapterId == b.chapterId) return a.time < b.time
    val chapters = bookRepository.get(a.bookId)?.chapters?.map { it.id } ?: return false
    val indexA = chapters.indexOf(a.chapterId)
    val indexB = chapters.indexOf(b.chapterId)
    return indexA >= 0 && indexB >= 0 && indexA < indexB
  }

  /**
   * [position] is where they lost track. While they can still take back going there, [beforeGoingBack] is where
   * it was before.
   */
  data class Spot(
    val position: PlaybackPosition,
    val beforeGoingBack: PlaybackPosition? = null,
    val wentBackAtMillis: Long = 0,
  )

  companion object {
    val TONIGHT = 12.hours

    /** How soon going forward after going back counts as fixing a slip. */
    val SLIP_TIME = 2.minutes
  }
}

/**
 * Something the listener did on purpose, which shows they're awake right then.
 */
internal fun ListeningEvent.isListenerAction(): Boolean = source in LISTENER_SOURCES && type in LISTENER_ACTIONS

private val LISTENER_SOURCES = setOf(
  ListeningEvent.Source.App,
  ListeningEvent.Source.Widget,
  ListeningEvent.Source.Notification,
  ListeningEvent.Source.Headset,
  ListeningEvent.Source.Bluetooth,
  ListeningEvent.Source.Car,
  ListeningEvent.Source.Watch,
)

private val LISTENER_ACTIONS = setOf(
  ListeningEvent.Type.Play,
  ListeningEvent.Type.Seek,
  ListeningEvent.Type.SkipBack,
  ListeningEvent.Type.SkipForward,
  ListeningEvent.Type.ChapterChange,
  ListeningEvent.Type.BookmarkJump,
  ListeningEvent.Type.JumpBack,
)
