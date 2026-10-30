package voice.core.summaries

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import voice.core.datastore.VoiceDataStoreFactory

/** Who writes the summaries. */
@Serializable
public enum class SummaryProvider {
  Haiku,
  Sonnet,
  Ollama,
}

/** The model the listener picked for summaries, set on the summaries screen. */
@Serializable
public data class SummarySettings(
  val provider: SummaryProvider = SummaryProvider.Haiku,
  /** The listener's own Anthropic API key, used for [SummaryProvider.Haiku] and [SummaryProvider.Sonnet]. */
  val claudeKey: String = "",
  /** Where Ollama listens, for example http://192.168.1.20:11434. */
  val ollamaUrl: String = "",
  val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
) {

  /** Whether everything the [provider] needs is filled in. */
  public val configured: Boolean
    get() = when (provider) {
      SummaryProvider.Haiku, SummaryProvider.Sonnet -> claudeKey.isNotBlank()
      SummaryProvider.Ollama -> ollamaUrl.isNotBlank() && ollamaModel.isNotBlank()
    }

  /** A short name of the model, stored with each summary. */
  public val modelName: String
    get() = when (provider) {
      SummaryProvider.Haiku -> CLAUDE_HAIKU
      SummaryProvider.Sonnet -> CLAUDE_SONNET
      SummaryProvider.Ollama -> ollamaModel.trim()
    }

  public companion object {
    public const val DEFAULT_OLLAMA_MODEL: String = "qwen3:14b"
    public const val CLAUDE_HAIKU: String = "claude-haiku-5-5"
    public const val CLAUDE_SONNET: String = "claude-sonnet-5-5"
  }
}

@SingleIn(AppScope::class)
public class SummarySettingsStore(store: DataStore<SummarySettings>) : DataStore<SummarySettings> by store {

  @Inject
  public constructor(factory: VoiceDataStoreFactory) : this(
    factory.create(
      serializer = SummarySettings.serializer(),
      fileName = "summarySettings",
      defaultValue = SummarySettings(),
    ),
  )
}
