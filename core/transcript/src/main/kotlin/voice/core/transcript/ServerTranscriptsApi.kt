package voice.core.transcript

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import voice.core.server.VoiceServer
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One book file the server has a transcript for, as `GET /api/transcripts` lists it. [name] is the audio file's name
 * without its extension, for example "The Waste Lands [B002V0QK4C]".
 */
@Serializable
public data class ServerTranscript(
  val name: String,
  val json: String,
  val lrc: String? = null,
  val bytes: Long = 0,
  val updatedAt: String? = null,
  /** The book's chapter and half-hour summaries, once the server wrote some. */
  val summaries: String? = null,
  val summariesUpdatedAt: String? = null,
)

/** A stretch of one audio file, for [ServerTranscriptsApi.summarize]. */
@Serializable
public data class ServerSummaryPart(
  /** The audio file's name, like a transcript is matched by. */
  val file: String,
  val startMs: Long,
  val endMs: Long,
)

public sealed interface ServerResult<out T> {
  public data class Success<T>(val value: T) : ServerResult<T>

  /** Why the server couldn't be used, in words for the settings screen. */
  public data class Failure(val reason: String) : ServerResult<Nothing>
}

public interface ServerTranscriptsApi {

  public suspend fun list(server: VoiceServer): ServerResult<List<ServerTranscript>>

  /** Writes the transcript [file] to [to], replacing it only once the whole file has arrived. */
  public suspend fun download(
    server: VoiceServer,
    file: String,
    to: File,
  ): ServerResult<Unit>

  /** Asks the server to transcribe the audio file [name] (without its extension). */
  public suspend fun request(
    server: VoiceServer,
    name: String,
  ): ServerResult<Unit>

  /** Has the server summarize [parts] of its transcripts. [label] says what the parts are, like a chapter's name. */
  public suspend fun summarize(
    server: VoiceServer,
    book: String,
    label: String,
    parts: List<ServerSummaryPart>,
  ): ServerResult<String>

  /** Has the server put [notes] on consecutive stretches of a part, in order, together into one summary. */
  public suspend fun combine(
    server: VoiceServer,
    book: String,
    label: String,
    notes: List<String>,
  ): ServerResult<String>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class OkHttpServerTranscriptsApi : ServerTranscriptsApi {

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  private val json = Json {
    ignoreUnknownKeys = true
    // the server needs every field
    encodeDefaults = true
  }

  // the server writes a summary with its local model, which takes a while
  private val slowClient = client.newBuilder()
    .readTimeout(2, TimeUnit.MINUTES)
    .build()

  override suspend fun list(server: VoiceServer): ServerResult<List<ServerTranscript>> {
    return get(server, emptyList()) { body ->
      json.decodeFromString(ListSerializer(ServerTranscript.serializer()), body.string())
    }
  }

  override suspend fun download(
    server: VoiceServer,
    file: String,
    to: File,
  ): ServerResult<Unit> {
    return get(server, listOf(file)) { body ->
      to.parentFile?.mkdirs()
      val partial = File(to.path + ".part")
      partial.outputStream().use { body.byteStream().copyTo(it) }
      if (!partial.renameTo(to)) {
        partial.delete()
        throw IOException("Could not save $to")
      }
    }
  }

  override suspend fun request(
    server: VoiceServer,
    name: String,
  ): ServerResult<Unit> {
    val body = json.encodeToString(TranscriptRequest.serializer(), TranscriptRequest(name))
    return post(server, "requests", body, client) { }
  }

  override suspend fun summarize(
    server: VoiceServer,
    book: String,
    label: String,
    parts: List<ServerSummaryPart>,
  ): ServerResult<String> {
    val body = json.encodeToString(SummaryRequest.serializer(), SummaryRequest(book, label, parts))
    return post(server, "summarize", body, slowClient) { text ->
      json.decodeFromString(SummaryResponse.serializer(), text).summary
    }
  }

  override suspend fun combine(
    server: VoiceServer,
    book: String,
    label: String,
    notes: List<String>,
  ): ServerResult<String> {
    val body = json.encodeToString(CombineRequest.serializer(), CombineRequest(book, label, notes))
    return post(server, "summarize", body, slowClient) { text ->
      json.decodeFromString(SummaryResponse.serializer(), text).summary
    }
  }

  private suspend fun <T> post(
    server: VoiceServer,
    path: String,
    body: String,
    client: OkHttpClient,
    read: (String) -> T,
  ): ServerResult<T> {
    val url = "${server.url}/api/transcripts".toHttpUrlOrNull()
      ?.newBuilder()
      ?.addPathSegment(path)
      ?.build()
      ?: return ServerResult.Failure("Not a server address: ${server.url}")
    val request = Request.Builder()
      .url(url)
      .header("X-API-Key", server.key)
      .post(body.toRequestBody("application/json".toMediaType()))
      .build()
    return try {
      client.newCall(request).executeAsync().use { response ->
        val text = withContext(Dispatchers.IO) { response.body.string() }
        when {
          response.isSuccessful -> ServerResult.Success(read(text))
          response.code == 401 -> ServerResult.Failure("The server didn't accept the key")
          else -> ServerResult.Failure(errorMessage(text) ?: "The server answered ${response.code}")
        }
      }
    } catch (e: IOException) {
      ServerResult.Failure(e.message ?: e.javaClass.simpleName)
    } catch (e: IllegalArgumentException) {
      ServerResult.Failure("The server sent an answer the app doesn't understand")
    }
  }

  private fun errorMessage(body: String): String? = try {
    json.decodeFromString(ErrorResponse.serializer(), body).error
  } catch (e: IllegalArgumentException) {
    null
  }

  private suspend fun <T> get(
    server: VoiceServer,
    path: List<String>,
    read: (okhttp3.ResponseBody) -> T,
  ): ServerResult<T> {
    val url = "${server.url}/api/transcripts".toHttpUrlOrNull()
      ?.newBuilder()
      ?.apply { path.forEach { addPathSegment(it) } }
      ?.build()
      ?: return ServerResult.Failure("Not a server address: ${server.url}")
    val request = Request.Builder().url(url).header("X-API-Key", server.key).build()
    return try {
      client.newCall(request).executeAsync().use { response ->
        when {
          response.isSuccessful -> ServerResult.Success(withContext(Dispatchers.IO) { read(response.body) })
          response.code == 401 -> ServerResult.Failure("The server didn't accept the key")
          response.code == 503 -> ServerResult.Failure("The server has no transcripts set up")
          else -> ServerResult.Failure("The server answered ${response.code}")
        }
      }
    } catch (e: IOException) {
      ServerResult.Failure(e.message ?: e.javaClass.simpleName)
    } catch (e: IllegalArgumentException) {
      ServerResult.Failure("The server sent something that isn't a transcript list")
    }
  }
}

@Serializable
private data class TranscriptRequest(val name: String)

@Serializable
private data class SummaryRequest(
  val book: String,
  val label: String,
  val parts: List<ServerSummaryPart>,
)

@Serializable
private data class CombineRequest(
  val book: String,
  val label: String,
  val notes: List<String>,
)

@Serializable
private data class SummaryResponse(val summary: String)

@Serializable
private data class ErrorResponse(val error: String? = null)
