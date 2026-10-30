package voice.core.transcript

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import voice.core.datastore.VoiceDataStoreFactory

/**
 * Which transcripts came from the listener's server (see [voice.core.server.VoiceServerConfig]), and how the last
 * look at the server went.
 */
@Serializable
public data class TranscriptDownloadSettings(
  /** Chapter id to the server's `updatedAt` of the transcript that was downloaded for it. */
  val downloaded: Map<String, String> = emptyMap(),
  val lastCheckAtMillis: Long? = null,
  /** Why the last check failed, until one works again. */
  val lastError: String? = null,
)

@SingleIn(AppScope::class)
public class TranscriptDownloadStore(store: DataStore<TranscriptDownloadSettings>) : DataStore<TranscriptDownloadSettings> by store {

  @Inject
  public constructor(factory: VoiceDataStoreFactory) : this(
    factory.create(
      serializer = TranscriptDownloadSettings.serializer(),
      fileName = "transcriptDownloads",
      defaultValue = TranscriptDownloadSettings(),
    ),
  )
}
