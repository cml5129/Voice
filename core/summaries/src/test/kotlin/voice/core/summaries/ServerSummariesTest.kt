package voice.core.summaries

import kotlinx.coroutines.test.runTest
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.transcript.TranscriptFiles
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerSummariesTest {

  private val files = TranscriptFiles(Files.createTempDirectory("transcripts").toFile())
  private val summaries = ServerSummaries(files)

  private fun write(
    file: ChapterId,
    json: String,
  ) {
    files.summariesFile(file).apply { parentFile?.mkdirs() }.writeText(json)
  }

  @Test
  fun `chapters and half hours of a one file book are matched to its parts`() = runTest {
    val marks = listOf(MarkData(0, "One"), MarkData(40 * MINUTE, "Two"))
    val book = book(listOf(chapter(fileA, 70 * MINUTE, marks)))
    write(
      fileA,
      """
      {"version":1,"model":"qwen3:14b","complete":false,
       "chapters":[{"title":"One","startMs":0,"endMs":2400000,"summary":"Roland walks."},
                   {"title":"Two","startMs":2401200,"endMs":4200000,"summary":"Eddie carves."}],
       "halfHours":[{"startMs":0,"endMs":1800000,"summary":"The first half hour."}]}
      """.trimIndent(),
    )

    val result = summaries.forBook(book)

    val chapters = book.chapterParts()
    assertEquals("Roland walks.", result.getValue(chapters[0].key).text)
    assertEquals(chapters[0].endMs, result.getValue(chapters[0].key).coveredUntilMs)
    assertEquals("Eddie carves.", result.getValue(chapters[1].key).text)
    assertEquals("qwen3:14b", result.getValue(chapters[1].key).model)
    val halfHours = book.halfHourParts()
    assertEquals("The first half hour.", result.getValue(halfHours[0].key).text)
    assertEquals(null, result[halfHours[1].key])
  }

  @Test
  fun `half hours are left out when the book has more than one file`() = runTest {
    val book = book(listOf(chapter(fileA, 60 * MINUTE), chapter(fileB, 60 * MINUTE)))
    write(
      fileB,
      """
      {"model":"m","chapters":[{"startMs":0,"endMs":3600000,"summary":"B."}],
       "halfHours":[{"startMs":0,"endMs":1800000,"summary":"H."}]}
      """.trimIndent(),
    )

    val result = summaries.forBook(book)

    assertEquals(mapOf(book.chapterParts()[1].key to "B."), result.mapValues { it.value.text })
  }

  @Test
  fun `a broken or missing file gives nothing`() = runTest {
    val book = book(listOf(chapter(fileA, 60 * MINUTE)))
    assertEquals(emptyMap(), summaries.forBook(book))
    write(fileA, "{not json")
    assertEquals(emptyMap(), summaries.forBook(book))
  }
}
