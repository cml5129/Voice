package voice.core.playback

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import voice.core.datastore.VoiceDataStoreFactory

/** Whether pressing a volume button twice in a row skips back or forward. */
@SingleIn(AppScope::class)
class VolumeSkipStore(store: DataStore<Boolean>) : DataStore<Boolean> by store {

  @Inject
  constructor(factory: VoiceDataStoreFactory) : this(
    factory.boolean("volumeSkip", defaultValue = false),
  )
}
