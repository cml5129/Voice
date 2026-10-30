package voice.core.summaries

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Writes text from instructions, with whichever model the listener picked. */
public interface SummaryModel {

  /** How much transcript text fits into one request. */
  public val maxInputChars: Int

  public suspend fun complete(
    system: String,
    prompt: String,
  ): String
}

/** A summary that couldn't be written, with the reason in words for the listener. */
public class SummaryException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

public interface SummaryModels {

  /** The model [settings] pick. Throws [SummaryException] while they are incomplete. */
  public suspend fun model(settings: SummarySettings): SummaryModel
}

/** Clients are kept, so their connections are reused. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class DefaultSummaryModels : SummaryModels {

  private val lock = Mutex()
  private var claude: Pair<String, AnthropicClient>? = null

  private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    // a local model takes its time on a long transcript
    .readTimeout(5, TimeUnit.MINUTES)
    .build()

  override suspend fun model(settings: SummarySettings): SummaryModel {
    if (!settings.configured) throw SummaryException("Pick a model for summaries first")
    return when (settings.provider) {
      SummaryProvider.Haiku, SummaryProvider.Sonnet -> ClaudeSummaryModel(claudeClient(settings.claudeKey.trim()), settings.modelName)
      SummaryProvider.Ollama -> OllamaSummaryModel(http, settings.ollamaUrl.trim().trimEnd('/'), settings.modelName)
    }
  }

  private suspend fun claudeClient(key: String): AnthropicClient = lock.withLock {
    claude?.takeIf { it.first == key }?.second ?: AnthropicOkHttpClient.builder()
      .apiKey(key)
      .timeout(Duration.ofMinutes(5))
      .build()
      .also { claude = key to it }
  }
}

internal class ClaudeSummaryModel(
  private val client: AnthropicClient,
  private val model: String,
) : SummaryModel {

  // about 100k tokens, a few hours of speech
  override val maxInputChars: Int = 400_000

  override suspend fun complete(
    system: String,
    prompt: String,
  ): String = withContext(Dispatchers.IO) {
    val params = MessageCreateParams.builder()
      .model(model)
      .maxTokens(MAX_TOKENS)
      .system(system)
      .addUserMessage(prompt)
      .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
      .build()
    val message = try {
      client.messages().create(params)
    } catch (e: UnauthorizedException) {
      throw SummaryException("Claude didn't accept the API key", e)
    } catch (e: AnthropicServiceException) {
      throw SummaryException("Claude couldn't answer (error ${e.statusCode()})", e)
    } catch (e: AnthropicException) {
      throw SummaryException("Couldn't reach Claude", e)
    }
    when (message.stopReason().orElse(null)) {
      StopReason.REFUSAL -> throw SummaryException("Claude declined to summarize this part")
      StopReason.MAX_TOKENS -> throw SummaryException("Claude's summary got cut off")
      else -> Unit
    }
    val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").trim()
    if (text.isEmpty()) throw SummaryException("Claude returned no summary")
    text
  }

  private companion object {
    // the summary is short, the rest leaves room for thinking
    const val MAX_TOKENS = 4_000L
  }
}

internal class OllamaSummaryModel(
  private val client: OkHttpClient,
  private val url: String,
  private val model: String,
) : SummaryModel {

  // fits the context window asked for below, with room for the answer
  override val maxInputChars: Int = 40_000

  private val json = Json {
    ignoreUnknownKeys = true
    // stream and think have to be sent, Ollama streams by default
    encodeDefaults = true
  }

  override suspend fun complete(
    system: String,
    prompt: String,
  ): String {
    val endpoint = "$url/api/chat".toHttpUrlOrNull() ?: throw SummaryException("The Ollama address isn't valid")
    val body = OllamaChatRequest(
      model = model,
      messages = listOf(OllamaMessage("system", system), OllamaMessage("user", prompt)),
      options = OllamaOptions(numCtx = CONTEXT_TOKENS),
    )
    val request = Request.Builder()
      .url(endpoint)
      .post(json.encodeToString(OllamaChatRequest.serializer(), body).toRequestBody("application/json".toMediaType()))
      .build()
    val answer = try {
      client.newCall(request).executeAsync().use { response ->
        val text = withContext(Dispatchers.IO) { response.body.string() }
        when {
          response.code == 404 -> throw SummaryException("Ollama doesn't have the model $model")
          !response.isSuccessful -> throw SummaryException("Ollama couldn't answer (error ${response.code})")
          else -> json.decodeFromString(OllamaChatResponse.serializer(), text).message.content
        }
      }
    } catch (e: IOException) {
      throw SummaryException("Couldn't reach Ollama at $url", e)
    } catch (e: IllegalArgumentException) {
      throw SummaryException("Ollama sent an answer the app doesn't understand", e)
    }
    // models that think anyway put it in front of the answer
    val text = answer.replace(THINKING, "").trim()
    if (text.isEmpty()) throw SummaryException("Ollama returned no summary")
    return text
  }

  private companion object {
    const val CONTEXT_TOKENS = 16_384
    val THINKING = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
  }
}

@Serializable
internal data class OllamaChatRequest(
  val model: String,
  val messages: List<OllamaMessage>,
  val options: OllamaOptions,
  val stream: Boolean = false,
  val think: Boolean = false,
)

@Serializable
internal data class OllamaMessage(
  val role: String,
  val content: String,
)

@Serializable
internal data class OllamaOptions(
  @SerialName("num_ctx")
  val numCtx: Int,
)

@Serializable
internal data class OllamaChatResponse(val message: OllamaMessage)
