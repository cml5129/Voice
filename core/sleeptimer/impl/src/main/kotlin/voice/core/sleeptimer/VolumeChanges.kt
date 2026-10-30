package voice.core.sleeptimer

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The listener changing the media volume, on the phone or on headphones. It's a sign they're awake.
 */
@SingleIn(AppScope::class)
@Inject
class VolumeChanges {

  val changes: SharedFlow<Unit>
    field = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  fun onChanged() {
    changes.tryEmit(Unit)
  }
}
