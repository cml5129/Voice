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
 * The server this build came with, from the VOICE_SERVER_URL and VOICE_API_KEY environment variables when it was
 * built. Empty when they weren't set.
 */
public data class VoiceServerDefaults(
  val url: String,
  val key: String,
)

@Serializable
public data class VoiceServerSettings(
  val enabled: Boolean = true,
  /** Replaces the server this build came with, unless blank. */
  val url: String = "",
  /** Replaces the key this build came with, unless blank. */
  val key: String = "",
) {

  /**
   * Null when switched off, or without a server and key from this build or set on the device.
   */
  public fun server(defaults: VoiceServerDefaults): VoiceServer? {
    if (!enabled) return null
    val url = url.ifBlank { defaults.url }.trim().trimEnd('/')
    val key = key.ifBlank { defaults.key }.trim()
    return if (url.isBlank() || key.isBlank()) null else VoiceServer(url, key)
  }

  /**
   * Values that match the build's are not stored, so a later build with another server takes over.
   */
  public fun withServer(
    url: String,
    key: String,
    defaults: VoiceServerDefaults,
  ): VoiceServerSettings {
    val trimmedUrl = url.trim().trimEnd('/')
    val trimmedKey = key.trim()
    return copy(
      url = if (trimmedUrl == defaults.url.trim().trimEnd('/')) "" else trimmedUrl,
      key = if (trimmedKey == defaults.key.trim()) "" else trimmedKey,
    )
  }
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
public class VoiceServerConfig(
  private val store: VoiceServerStore,
  public val defaults: VoiceServerDefaults,
) {

  /** Null while switched off or not set up. */
  public val server: Flow<VoiceServer?> = store.data.map { it.server(defaults) }.distinctUntilChanged()

  public suspend fun current(): VoiceServer? = server.first()
}
