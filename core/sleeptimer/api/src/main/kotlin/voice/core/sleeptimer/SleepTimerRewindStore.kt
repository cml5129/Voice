package voice.core.sleeptimer

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.builtins.nullable
import voice.core.datastore.VoiceDataStoreFactory

@SingleIn(AppScope::class)
class SleepTimerRewindStore(store: DataStore<SleepTimerRewind?>) : DataStore<SleepTimerRewind?> by store {

  @Inject
  constructor(factory: VoiceDataStoreFactory) : this(
    factory.create(
      serializer = SleepTimerRewind.serializer().nullable,
      fileName = "sleepTimerRewind",
      defaultValue = null,
    ),
  )
}
