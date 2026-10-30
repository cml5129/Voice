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
import okhttp3.OkHttpClient
import okhttp3.Request
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
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class OkHttpServerTranscriptsApi : ServerTranscriptsApi {

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  private val json = Json { ignoreUnknownKeys = true }

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
