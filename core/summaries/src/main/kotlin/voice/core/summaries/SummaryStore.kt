package voice.core.summaries

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import voice.core.data.BookId
import voice.core.logging.api.Logger
import java.io.File
import java.security.MessageDigest

@Serializable
public data class SavedSummary(
  val text: String,
  /** How far into the book the summary goes, as a part that was still playing ends early. */
  val coveredUntilMs: Long,
  val model: String,
  val writtenAtMillis: Long,
)

@Serializable
internal data class SavedSummaries(val byKey: Map<String, SavedSummary> = emptyMap())

/** The summaries written so far, one file per book in the app's private storage. */
@SingleIn(AppScope::class)
public class SummaryStore(private val dir: File) {

  @Inject
  public constructor(context: Context) : this(File(context.filesDir, "summaries"))

  private val lock = Mutex()
  private val changes = MutableStateFlow(0)
  private val json = Json { ignoreUnknownKeys = true }

  /** By [SummaryPart.key], updated as summaries are saved. */
  public fun summaries(bookId: BookId): Flow<Map<String, SavedSummary>> = changes
    .onStart { emit(changes.value) }
    .map { lock.withLock { read(bookId) }.byKey }

  public suspend fun save(
    bookId: BookId,
    key: String,
    summary: SavedSummary,
  ) {
    lock.withLock {
      val saved = read(bookId)
      write(bookId, saved.copy(byKey = saved.byKey + (key to summary)))
    }
    changes.value++
  }

  private suspend fun read(bookId: BookId): SavedSummaries = withContext(Dispatchers.IO) {
    val file = file(bookId)
    if (!file.isFile) return@withContext SavedSummaries()
    try {
      json.decodeFromString(SavedSummaries.serializer(), file.readText())
    } catch (e: SerializationException) {
      Logger.w(e, "Could not read the summaries at $file")
      SavedSummaries()
    }
  }

  private suspend fun write(
    bookId: BookId,
    summaries: SavedSummaries,
  ) = withContext(Dispatchers.IO) {
    val file = file(bookId)
    dir.mkdirs()
    val partial = File(file.path + ".part")
    partial.writeText(json.encodeToString(SavedSummaries.serializer(), summaries))
    if (!partial.renameTo(file)) {
      file.delete()
      check(partial.renameTo(file)) { "Could not save the summaries at $file" }
    }
  }

  private fun file(bookId: BookId): File {
    val hash = MessageDigest.getInstance("SHA-1")
      .digest(bookId.value.toByteArray())
      .take(8)
      .joinToString("") { "%02x".format(it) }
    return File(dir, "$hash.json")
  }
}
