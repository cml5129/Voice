package voice.core.server

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import voice.core.datastore.VoiceDataStoreFactory

/**
 * The listener's own server, which keeps the listening history and serves transcripts.
 */
public data class VoiceServer(
  /** Without a trailing slash, for example https://thechrislorenz.com. */
  val url: String,
  /** Sent as X-API-Key. The server only lets it add history and read transcripts. */
  val key: String,
)

/**
 * Set in Settings, so nothing about the server is built into the app.
 */
@Serializable
public data class VoiceServerSettings(
  val enabled: Boolean = true,
  val url: String = "",
  val key: String = "",
) {

  /**
   * Null when switched off, or without a server address and key.
   */
  public fun server(): VoiceServer? {
    if (!enabled) return null
    val url = url.trim().trimEnd('/')
    val key = key.trim()
    return if (url.isBlank() || key.isBlank()) null else VoiceServer(url, key)
  }

  public fun withServer(
    url: String,
    key: String,
  ): VoiceServerSettings = copy(url = url.trim().trimEnd('/'), key = key.trim())
}

@SingleIn(AppScope::class)
public class VoiceServerStore(store: DataStore<VoiceServerSettings>) : DataStore<VoiceServerSettings> by store {

  @Inject
  public constructor(factory: VoiceDataStoreFactory) : this(
    factory.create(
      serializer = VoiceServerSettings.serializer(),
      fileName = "voiceServer",
      defaultValue = VoiceServerSettings(),
    ),
  )
}

/**
 * The server everything that talks to the listener's server uses.
 */
@SingleIn(AppScope::class)
@Inject
public class VoiceServerConfig(private val store: VoiceServerStore) {

  /** Null while switched off or not set up. */
  public val server: Flow<VoiceServer?> = store.data.map { it.server() }.distinctUntilChanged()

  public suspend fun current(): VoiceServer? = server.first()
}
