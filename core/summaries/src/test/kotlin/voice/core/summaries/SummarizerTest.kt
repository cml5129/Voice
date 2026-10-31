package voice.core.summaries

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import voice.core.transcript.TranscriptFiles
import voice.core.transcript.TranscriptRepository
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SummarizerTest {

  private val transcriptFiles = TranscriptFiles(Files.createTempDirectory("transcripts").toFile())
  private val store = SummaryStore(Files.createTempDirectory("summaries").toFile())
  private val model = FakeModel()
  private val settings = MemoryDataStore(SummarySettings(provider = SummaryProvider.Haiku, claudeKey = "key"))
  private val summarizer = Summarizer(
    settings = SummarySettingsStore(settings),
    models = FakeModels(model),
    transcripts = TranscriptRepository(transcriptFiles),
    store = store,
    clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC),
  )

  private val chapters = listOf(chapter(fileA, 60 * MINUTE), chapter(fileB, 60 * MINUTE))

  init {
    transcriptFiles.transcriptFile(fileA).apply { parentFile?.mkdirs() }.writeText(
      cues(0L to "Roland walks.", 40 * MINUTE to "Eddie carves a key."),
    )
    transcriptFiles.transcriptFile(fileB).writeText(
      cues(10 * MINUTE to "The bear guardian attacks.", 50 * MINUTE to "Blaine the train speaks."),
    )
  }

  @Test
  fun `only what was heard goes to the model, and the summary is saved`() = runTest {
    val book = book(chapters, currentChapter = fileB, positionInChapter = 20 * MINUTE)
    val part = book.halfHourParts()[2]

    val summary = summarizer.summarize(book, part)

    assertEquals("A summary.", summary.text)
    assertEquals(80 * MINUTE, summary.coveredUntilMs)
    assertEquals(SummarySettings.CLAUDE_HAIKU, summary.model)
    val prompt = model.prompts.single()
    assertTrue("The bear guardian attacks." in prompt, prompt)
    assertTrue("Blaine" !in prompt, prompt)
    assertTrue("Eddie" !in prompt, prompt)
    assertTrue("Book: The Waste Lands by Stephen King" in prompt, prompt)
    assertEquals(mapOf(part.key to summary), store.summaries(book.id).first())
  }

  @Test
  fun `a part across two files gets the text of both`() = runTest {
    val book = book(chapters, currentChapter = fileB, positionInChapter = 59 * MINUTE)
    val part = SummaryPart(SummaryKind.Session, "session:1", startMs = 30 * MINUTE, endMs = 75 * MINUTE)

    val _ = summarizer.summarize(book, part)

    val prompt = model.prompts.single()
    assertTrue("Eddie carves a key. The bear guardian attacks." in prompt, prompt)
  }

  @Test
  fun `a part not heard yet is not summarized`() = runTest {
    val book = book(chapters, currentChapter = fileA, positionInChapter = 10 * MINUTE)
    val part = book.halfHourParts()[1]

    assertEquals(false, summarizer.canSummarize(book, part))
    assertFailsWith<SummaryException> { summarizer.summarize(book, part) }
    assertEquals(emptyList(), model.prompts)
  }

  @Test
  fun `a long text is summarized in stretches, then put together`() = runTest {
    model.maxInputChars = 20
    val book = book(chapters, currentChapter = fileB, positionInChapter = 59 * MINUTE)

    val _ = summarizer.summarize(book, book.chapterParts()[0])

    assertEquals(3, model.prompts.size)
    assertTrue("Stretch 1 of 2" in model.prompts[0], model.prompts[0])
    assertTrue("Stretch 2:\nA summary." in model.prompts[2], model.prompts[2])
  }

  @Test
  fun `the text is split at spaces`() {
    assertEquals(listOf("one two", "three"), splitText("one two three", maxChars = 9))
    assertEquals(listOf("short"), splitText("short", maxChars = 9))
  }

  @Test
  fun `positions read as hours, minutes and seconds`() {
    assertEquals("1:05:09", formatPosition((3600 + 5 * 60 + 9) * 1000L))
  }
}

private fun cues(vararg cues: Pair<Long, String>): String = cues.joinToString(prefix = "[", postfix = "]") { (start, text) ->
  """{"timestamp_start":$start,"timestamp_end":${start + 1000},"text":"$text"}"""
}

private class FakeModel : SummaryModel {

  override var maxInputChars: Int = 10_000
  val prompts = mutableListOf<String>()

  override suspend fun complete(
    system: String,
    prompt: String,
  ): String {
    prompts += prompt
    return "A summary."
  }
}

private class FakeModels(private val model: SummaryModel) : SummaryModels {
  override suspend fun model(settings: SummarySettings): SummaryModel = model
}
