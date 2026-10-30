package voice.core.historyupload

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import voice.core.datastore.VoiceDataStoreFactory
import voice.core.server.VoiceServer

/**
 * How far the listening history got to the listener's server (see [voice.core.server.VoiceServerConfig]), so that it
 * is kept past the newest events per book this device holds on to.
 */
@Serializable
public data class HistoryUploadSettings(
  /** Unused: the server is shared with transcripts now. Kept so files written before still read. */
  val serverUrl: String = "",
  /** Unused, like [serverUrl]. */
  val key: String = "",
  /** Tells the server which events came from this device, as event ids are only unique on one. */
  val deviceId: String? = null,
  /** The newest event that [uploadedServer] has. The ones after it still have to go up. */
  val uploadedUpToId: Long = 0,
  val uploadedServer: String? = null,
  val lastUploadAtMillis: Long? = null,
  /** Why the last upload failed, until one works again. */
  val lastError: String? = null,
) {

  /**
   * Another server has none of the history yet, so all of it goes up again.
   */
  public fun uploadedUpToId(server: VoiceServer): Long = if (server.url == uploadedServer) uploadedUpToId else 0
}

@SingleIn(AppScope::class)
public class HistoryUploadStore(store: DataStore<HistoryUploadSettings>) : DataStore<HistoryUploadSettings> by store {

  @Inject
  public constructor(factory: VoiceDataStoreFactory) : this(
    factory.create(
      serializer = HistoryUploadSettings.serializer(),
      fileName = "historyUpload",
      defaultValue = HistoryUploadSettings(),
    ),
  )
}
