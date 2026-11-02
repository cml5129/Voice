package voice.core.sleeptimer

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class LastNightStartTest {

  private val start = LocalTime.of(22, 0)
  private val end = LocalTime.of(6, 0)

  @Test
  fun `during the night it doesn't ask yet`() {
    assertEquals(expected = null, actual = lastNightStart(LocalDateTime.of(2026, 11, 2, 3, 0), start, end))
  }

  @Test
  fun `after the night it asks about the night that started the day before`() {
    assertEquals(expected = LocalDateTime.of(2026, 11, 1, 22, 0), actual = lastNightStart(LocalDateTime.of(2026, 11, 2, 8, 0), start, end))
  }

  @Test
  fun `once tonight started it no longer asks`() {
    assertEquals(expected = null, actual = lastNightStart(LocalDateTime.of(2026, 11, 2, 22, 30), start, end))
  }

  @Test
  fun `hours within one day`() {
    val start = LocalTime.of(1, 0)
    assertEquals(expected = LocalDateTime.of(2026, 11, 2, 1, 0), actual = lastNightStart(LocalDateTime.of(2026, 11, 2, 8, 0), start, end))
  }
}
