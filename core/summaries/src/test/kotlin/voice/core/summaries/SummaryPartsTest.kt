package voice.core.summaries

import voice.core.data.ListeningEvent.Type
import voice.core.data.MarkData
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryPartsTest {

  private val book = book(
    chapters = listOf(
      chapter(fileA, 70 * MINUTE, marks = listOf(MarkData(0, "One"), MarkData(40 * MINUTE, "Two"))),
      chapter(fileB, 20 * MINUTE),
    ),
  )

  @Test
  fun `chapters are placed in the whole book`() {
    val parts = book.chapterParts()

    assertEquals(listOf("One", "Two", fileB.value), parts.map { it.name })
    assertEquals(listOf(0L, 40 * MINUTE, 70 * MINUTE), parts.map { it.startMs })
    assertEquals(listOf(40 * MINUTE, 70 * MINUTE, 90 * MINUTE), parts.map { it.endMs })
    assertEquals("chapter:${fileA.value}@${40 * MINUTE}", parts[1].key)
  }

  @Test
  fun `half hours cover the book, the last one shorter`() {
    val parts = book.halfHourParts()

    assertEquals(listOf(0L, 30 * MINUTE, 60 * MINUTE), parts.map { it.startMs })
    assertEquals(listOf(30 * MINUTE, 60 * MINUTE, 90 * MINUTE), parts.map { it.endMs })
    assertEquals(listOf("half:0", "half:1", "half:2"), parts.map { it.key })
  }

  @Test
  fun `a long pause starts a new session, newest first`() {
    val events = listOf(
      event(1, Type.Play, atMinute = 0, chapterId = fileA, time = 0),
      event(2, Type.Pause, atMinute = 20, chapterId = fileA, time = 20 * MINUTE),
      event(3, Type.Play, atMinute = 120, chapterId = fileA, time = 20 * MINUTE),
      event(4, Type.Pause, atMinute = 140, chapterId = fileB, time = 5 * MINUTE),
    )

    val parts = book.sessionParts(events, nowMillis = 200 * MINUTE)

    assertEquals(listOf("session:3", "session:1"), parts.map { it.key })
    assertEquals(listOf(20 * MINUTE, 0L), parts.map { it.startMs })
    assertEquals(listOf(75 * MINUTE, 20 * MINUTE), parts.map { it.endMs })
    assertEquals(Instant.ofEpochMilli(120 * MINUTE), parts.first().startedAt)
  }

  @Test
  fun `a session ends where the listener fell asleep`() {
    val events = listOf(
      event(1, Type.Play, atMinute = 0, chapterId = fileA, time = 10 * MINUTE),
      event(2, Type.FellAsleep, atMinute = 15, chapterId = fileA, time = 25 * MINUTE),
      event(3, Type.SleepTimerEnded, atMinute = 25, chapterId = fileA, time = 35 * MINUTE),
    )

    val part = book.sessionParts(events, nowMillis = 60 * MINUTE).single()

    assertEquals(10 * MINUTE, part.startMs)
    assertEquals(25 * MINUTE, part.endMs)
  }

  @Test
  fun `a session still playing goes up to where the book is`() {
    val playing = book(book.chapters, currentChapter = fileA, positionInChapter = 50 * MINUTE)
    val events = listOf(event(1, Type.Play, atMinute = 0, chapterId = fileA, time = 30 * MINUTE))

    val part = playing.sessionParts(events, nowMillis = 20 * MINUTE).single()

    assertEquals(30 * MINUTE, part.startMs)
    assertEquals(50 * MINUTE, part.endMs)
  }

  @Test
  fun `a brief session is left out`() {
    val events = listOf(
      event(1, Type.Play, atMinute = 0, chapterId = fileA, time = 0),
      event(2, Type.Pause, atMinute = 0, chapterId = fileA, time = 20_000),
    )

    assertEquals(emptyList(), book.sessionParts(events, nowMillis = MINUTE))
  }
}
