package voice.core.sleeptimer

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import voice.core.datastore.VoiceDataStoreFactory

/**
 * The day, as an epoch day, whose morning check was last answered or dismissed, so it isn't asked again that day.
 */
@SingleIn(AppScope::class)
class MorningCheckAnsweredStore(store: DataStore<Long?>) : DataStore<Long?> by store {

  @Inject
  constructor(factory: VoiceDataStoreFactory) : this(
    factory.create(
      serializer = Long.serializer().nullable,
      fileName = "morningCheckAnswered",
      defaultValue = null,
    ),
  )
}
