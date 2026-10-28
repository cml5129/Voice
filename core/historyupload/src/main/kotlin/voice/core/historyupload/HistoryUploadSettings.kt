package voice.core.historyupload

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import voice.core.datastore.VoiceDataStoreFactory

/**
 * Where the listening history goes, so that it is kept past the newest events per book this device holds on to.
 */
@Serializable
public data class HistoryUploadSettings(
  /** For example https://thechrislorenz.com. Uploads go to /api/voice-history on it. */
  val serverUrl: String = "",
  /** Sent as X-API-Key. The server's key for this only lets the app add history. */
  val key: String = "",
  /** Tells the server which events came from this device, as event ids are only unique on one. */
  val deviceId: String? = null,
  /** The newest event the server has. The ones after it still have to go up. */
  val uploadedUpToId: Long = 0,
  val lastUploadAtMillis: Long? = null,
  /** Why the last upload failed, until one works again. */
  val lastError: String? = null,
) {

  val enabled: Boolean get() = serverUrl.isNotBlank() && key.isNotBlank()

  /**
   * A new server has none of the history yet, so all of it goes up again.
   */
  public fun withServer(
    serverUrl: String,
    key: String,
  ): HistoryUploadSettings {
    val url = serverUrl.trim().trimEnd('/')
    return copy(
      serverUrl = url,
      key = key.trim(),
      uploadedUpToId = if (url == this.serverUrl) uploadedUpToId else 0,
      lastError = null,
    )
  }
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
