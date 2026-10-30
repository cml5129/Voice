package voice.core.transcript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TranscriptCue(
  @SerialName("timestamp_start") val timestampStartMs: Long,
  @SerialName("timestamp_end") val timestampEndMs: Long,
  val text: String,
)
