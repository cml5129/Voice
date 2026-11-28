package voice.core.sleeptimer

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import voice.core.playback.history.PlaybackPosition
import java.time.Clock

/** The last places [ListeningCheck] knew the listener was awake, so [VolumeSkip] can go back or forward to one. */
@SingleIn(AppScope::class)
@Inject
class AwakeSpots(private val clock: Clock) {

  private var spots = emptyList<Spot>()

  fun add(position: PlaybackPosition) {
    if (spots.lastOrNull()?.position == position) return
    spots = (spots + Spot(position, clock.millis())).takeLast(MAX_SPOTS)
  }

  /** Newest first, only those known before [atMillis] of the clock. */
  fun before(atMillis: Long): List<PlaybackPosition> = spots.filter { it.atMillis < atMillis }.map { it.position }.asReversed()

  private data class Spot(
    val position: PlaybackPosition,
    val atMillis: Long,
  )

  private companion object {
    const val MAX_SPOTS = 50
  }
}
