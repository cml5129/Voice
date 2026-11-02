package voice.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadAlongTest {

  private val cues = listOf(
    TranscriptCue(timestampStartMs = 1_000, timestampEndMs = 4_000, text = "one"),
    TranscriptCue(timestampStartMs = 4_000, timestampEndMs = 8_000, text = "two"),
    // a pause between 8 and 10 seconds
    TranscriptCue(timestampStartMs = 10_000, timestampEndMs = 14_000, text = "three"),
  )

  @Test
  fun `nothing is read before the first line`() {
    assertEquals(-1, cues.cueIndexAt(500))
  }

  @Test
  fun `a line is read from its start`() {
    assertEquals(0, cues.cueIndexAt(1_000))
    assertEquals(1, cues.cueIndexAt(4_000))
    assertEquals(2, cues.cueIndexAt(12_000))
  }

  @Test
  fun `a pause keeps the line just read`() {
    assertEquals(1, cues.cueIndexAt(9_000))
  }

  @Test
  fun `the last line stays after the end`() {
    assertEquals(2, cues.cueIndexAt(60_000))
  }

  @Test
  fun `no cues`() {
    assertEquals(-1, emptyList<TranscriptCue>().cueIndexAt(1_000))
  }
}
