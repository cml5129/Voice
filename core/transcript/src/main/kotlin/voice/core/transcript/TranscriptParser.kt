package voice.core.transcript

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.InputStream

/**
 * Reads a transcript in any of the shapes audiobook tools write: the app's own list of
 * `{timestamp_start, timestamp_end, text}` in milliseconds, whisper.cpp's `{"transcription": [{offsets, text}]}`
 * (what OpenAudible exports as `<book>.m4b.json`), or openai-whisper's `{"segments": [{start, end, text}]}` in
 * seconds. Anything else throws, so callers can fall back to transcribing.
 */
object TranscriptParser {

  private val json = Json { ignoreUnknownKeys = true }

  fun parse(jsonContent: String): List<TranscriptCue> = cuesOf(json.parseToJsonElement(jsonContent))

  @OptIn(ExperimentalSerializationApi::class)
  fun parse(stream: InputStream): List<TranscriptCue> = cuesOf(json.decodeFromStream<JsonElement>(stream))

  /** The app's own format, which [parse] reads back fastest. */
  fun encode(cues: List<TranscriptCue>): String = json.encodeToString(ListSerializer(TranscriptCue.serializer()), cues)

  private fun cuesOf(root: JsonElement): List<TranscriptCue> = when {
    root is JsonArray -> json.decodeFromJsonElement<List<TranscriptCue>>(root)
    root is JsonObject && root["transcription"] is JsonArray -> (root["transcription"] as JsonArray).mapNotNull { entry ->
      val offsets = entry.jsonObject["offsets"]?.jsonObject ?: return@mapNotNull null
      cue(offsets["from"]?.jsonPrimitive?.longOrNull, offsets["to"]?.jsonPrimitive?.longOrNull, entry)
    }
    root is JsonObject && root["segments"] is JsonArray -> (root["segments"] as JsonArray).mapNotNull { entry ->
      val start = entry.jsonObject["start"]?.jsonPrimitive?.doubleOrNull
      val end = entry.jsonObject["end"]?.jsonPrimitive?.doubleOrNull
      cue(start?.let { (it * 1000).toLong() }, end?.let { (it * 1000).toLong() }, entry)
    }
    else -> throw IllegalArgumentException("Not a transcript")
  }

  private fun cue(
    startMs: Long?,
    endMs: Long?,
    entry: JsonElement,
  ): TranscriptCue? {
    val text = entry.jsonObject["text"]?.jsonPrimitive?.content?.trim().orEmpty()
    if (startMs == null || endMs == null || text.isEmpty()) return null
    return TranscriptCue(startMs, endMs, text)
  }
}
