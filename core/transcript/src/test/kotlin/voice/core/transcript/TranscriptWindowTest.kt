package voice.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptWindowTest {

  private val cues = listOf(
    TranscriptCue(timestampStartMs = 0, timestampEndMs = 10_000, text = "one"),
    TranscriptCue(timestampStartMs = 10_000, timestampEndMs = 20_000, text = "two"),
    TranscriptCue(timestampStartMs = 20_000, timestampEndMs = 30_000, text = "three"),
    TranscriptCue(timestampStartMs = 30_000, timestampEndMs = 40_000, text = "four"),
  )

  @Test
  fun `returns only cues within the preceding window`() {
    val text = cues.textForPrecedingWindow(positionMs = 35_000, windowMs = 15_000)

    assertEquals(expected = "three four", actual = text)
  }

  @Test
  fun `clamps window start at zero when position is near the beginning`() {
    val text = cues.textForPrecedingWindow(positionMs = 5_000, windowMs = 60_000)

    assertEquals(expected = "one", actual = text)
  }

  @Test
  fun `returns empty string when nothing precedes the position`() {
    val text = cues.textForPrecedingWindow(positionMs = 0, windowMs = 60_000)

    assertEquals(expected = "", actual = text)
  }
}
