package voice.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptParserTest {

  @Test
  fun `parses cues from whisper-style json`() {
    val cues = TranscriptParser.parse(
      """
      [
        { "timestamp_start": 0, "timestamp_end": 3240, "text": "\"This is Audible.\"" },
        { "timestamp_start": 3240, "timestamp_end": 12600, "text": "Sound Booth Theater presents" }
      ]
      """.trimIndent(),
    )

    assertEquals(
      expected = listOf(
        TranscriptCue(timestampStartMs = 0, timestampEndMs = 3240, text = "\"This is Audible.\""),
        TranscriptCue(timestampStartMs = 3240, timestampEndMs = 12600, text = "Sound Booth Theater presents"),
      ),
      actual = cues,
    )
  }

  @Test
  fun `parses empty transcript`() {
    assertEquals(expected = emptyList(), actual = TranscriptParser.parse("[]"))
  }

  @Test
  fun `parses the same cues from a stream`() {
    val json = """[{"timestamp_start":0,"timestamp_end":10,"text":"a","extra":1},{"timestamp_start":10,"timestamp_end":20,"text":"b"}]"""

    assertEquals(expected = TranscriptParser.parse(json), actual = TranscriptParser.parse(json.byteInputStream()))
  }
}
