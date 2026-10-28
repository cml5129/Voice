package voice.core.historyupload

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
public data class UploadBatch(
  val device: String,
  /** The time zone of the phone, so the server can tell which night an event belongs to. */
  val zone: String,
  val events: List<UploadEvent>,
)

/**
 * A [voice.core.data.ListeningEvent] with what the server can't look up: the book's name and where in the book
 * the event happened, besides where in the file.
 */
@Serializable
public data class UploadEvent(
  val id: Long,
  val bookId: String,
  val bookName: String?,
  val author: String?,
  val type: String,
  val source: String,
  val at: Long,
  val chapterId: String,
  val chapterName: String?,
  val time: Long,
  val bookTime: Long?,
  val toChapterId: String?,
  val toChapterName: String?,
  val toTime: Long?,
  val toBookTime: Long?,
)

public interface HistoryUploadApi {

  /**
   * Null when the server stored the batch, otherwise why not.
   */
  suspend fun send(
    serverUrl: String,
    key: String,
    batch: UploadBatch,
  ): String?
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class OkHttpHistoryUploadApi : HistoryUploadApi {

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private val json = Json { encodeDefaults = true }

  override suspend fun send(
    serverUrl: String,
    key: String,
    batch: UploadBatch,
  ): String? {
    val request = try {
      Request.Builder()
        .url("$serverUrl/api/voice-history")
        .header("X-API-Key", key)
        .post(json.encodeToString(UploadBatch.serializer(), batch).toRequestBody("application/json".toMediaType()))
        .build()
    } catch (e: IllegalArgumentException) {
      return "Not a server address: $serverUrl"
    }
    return try {
      client.newCall(request).executeAsync().use { response ->
        when {
          response.isSuccessful -> null
          response.code == 401 -> "The server didn't accept the key"
          else -> "The server answered ${response.code}"
        }
      }
    } catch (e: IOException) {
      e.message ?: e.javaClass.simpleName
    }
  }
}
