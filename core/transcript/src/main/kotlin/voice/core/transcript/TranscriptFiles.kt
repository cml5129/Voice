package voice.core.transcript

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import voice.core.data.ChapterId
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Where the downloaded transcripts are kept: one file per audio file, in the app's private storage.
 */
@SingleIn(AppScope::class)
public class TranscriptFiles(private val dir: File) {

  @Inject
  public constructor(context: Context) : this(File(context.filesDir, "transcripts"))

  /** The transcript of the audio file [chapterId], in a format [TranscriptParser] reads. */
  public fun transcriptFile(chapterId: ChapterId): File = File(dir, "${stableKey(chapterId.value)}.json")
}

/** A short file-name-safe key for a URI: the first 16 hex digits of its SHA-1. */
internal fun stableKey(value: String): String = MessageDigest.getInstance("SHA-1")
  .digest(value.toByteArray())
  .take(8)
  .joinToString("") { "%02x".format(it) }

/**
 * The audio file's name, read from its URI without asking the file system: the last segment of a file path or of a
 * document id like `primary:Audiobooks/The Waste Lands [B002V0QK4C].m4b`. Null when the URI doesn't say.
 */
public fun audioFileName(chapterId: ChapterId): String? {
  val decoded = try {
    URLDecoder.decode(chapterId.value.replace("+", "%2B"), "UTF-8")
  } catch (e: IllegalArgumentException) {
    return null
  }
  val name = decoded.substringBefore('?').substringAfterLast('/').substringAfterLast(':')
  return name.takeIf { it.contains('.') }
}

/**
 * How a transcript is matched to an audio file: the name without its extension, ignoring case, spaces and
 * punctuation, so small differences in how a download was named don't matter.
 */
public fun transcriptMatchKey(name: String): String {
  val withoutExtension = if (name.contains('.')) name.substringBeforeLast('.') else name
  return withoutExtension.lowercase().filter { it.isLetterOrDigit() }
}
