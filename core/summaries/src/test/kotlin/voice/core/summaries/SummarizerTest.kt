package voice.core.summaries

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import voice.core.server.VoiceServer
import voice.core.server.VoiceServerConfig
import voice.core.server.VoiceServerSettings
import voice.core.server.VoiceServerStore
import voice.core.transcript.ServerResult
import voice.core.transcript.ServerSummaryPart
import voice.core.transcript.ServerTranscript
import voice.core.transcript.ServerTranscriptsApi
import voice.core.transcript.TranscriptFiles
import voice.core.transcript.TranscriptRepository
import java.io.File
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
  private val serverSettings = MemoryDataStore(VoiceServerSettings(url = "https://example.com", key = "voice-key"))
  private val api = FakeApi()
  private val summarizer = Summarizer(
    settings = SummarySettingsStore(settings),
    models = FakeModels(model),
    transcripts = TranscriptRepository(transcriptFiles),
    store = store,
    clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC),
    serverConfig = VoiceServerConfig(VoiceServerStore(serverSettings)),
    api = api,
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

  private suspend fun useServer() {
    val _ = settings.updateData { it.copy(provider = SummaryProvider.Server) }
  }

  @Test
  fun `the server writes the summary from the part of each file`() = runTest {
    useServer()
    val book = book(chapters, currentChapter = fileB, positionInChapter = 15 * MINUTE)
    val part = SummaryPart(SummaryKind.Session, "session:1", startMs = 50 * MINUTE, endMs = 90 * MINUTE)

    val summary = summarizer.summarize(book, part)

    assertEquals("From the server.", summary.text)
    assertEquals(SummarySettings.SERVER, summary.model)
    assertEquals(90 * MINUTE, summary.coveredUntilMs)
    val asked = api.asked.single()
    assertEquals("The Waste Lands by Stephen King", asked.book)
    assertTrue("0:50:00 to 1:30:00" in asked.label, asked.label)
    assertEquals(
      listOf(
        ServerSummaryPart("a.m4b", startMs = 50 * MINUTE, endMs = 60 * MINUTE),
        ServerSummaryPart("b.m4b", startMs = 0, endMs = 30 * MINUTE),
      ),
      asked.parts,
    )
    assertEquals(emptyList(), model.prompts)
    assertEquals(mapOf(part.key to summary), store.summaries(book.id).first())
  }

  @Test
  fun `the server's reason shows when it can't summarize`() = runTest {
    useServer()
    api.failure = "The model on the server didn't answer"
    val book = book(chapters, currentChapter = fileB, positionInChapter = 15 * MINUTE)

    val error = assertFailsWith<SummaryException> { summarizer.summarize(book, book.halfHourParts()[0]) }

    assertEquals("The model on the server didn't answer", error.message)
  }

  @Test
  fun `without a server it says to set one up`() = runTest {
    useServer()
    val _ = serverSettings.updateData { it.copy(enabled = false) }
    val book = book(chapters, currentChapter = fileB, positionInChapter = 15 * MINUTE)

    assertFailsWith<SummaryException> { summarizer.summarize(book, book.halfHourParts()[0]) }
    assertEquals(emptyList(), api.asked)
  }

  @Test
  fun `a long stretch goes to the server in pieces, then their notes are put together`() = runTest {
    useServer()
    val book = book(chapters, currentChapter = fileA, positionInChapter = 0)
    val part = combinedPart(
      SummaryPart(SummaryKind.Session, "session:1", startMs = 0, endMs = 30 * MINUTE),
      SummaryPart(SummaryKind.Session, "session:2", startMs = 100 * MINUTE, endMs = 120 * MINUTE),
    )

    val summary = summarizer.summarize(book, part)

    assertEquals("All together.", summary.text)
    assertEquals(120 * MINUTE, summary.coveredUntilMs)
    assertEquals(2, api.asked.size)
    assertTrue("Stretch 1 of 2." in api.asked[0].label, api.asked[0].label)
    assertEquals(
      listOf(
        ServerSummaryPart("a.m4b", startMs = 0, endMs = 60 * MINUTE),
        ServerSummaryPart("b.m4b", startMs = 0, endMs = 30 * MINUTE),
      ),
      api.asked[0].parts,
    )
    assertEquals(listOf(ServerSummaryPart("b.m4b", startMs = 30 * MINUTE, endMs = 60 * MINUTE)), api.asked[1].parts)
    assertEquals(listOf(listOf("From the server.", "From the server.")), api.combined)
    assertEquals(mapOf(part.key to summary), store.summaries(book.id).first())
  }

  @Test
  fun `a session is summarized to its end even after going back`() = runTest {
    val book = book(chapters, currentChapter = fileA, positionInChapter = 5 * MINUTE)
    val part = SummaryPart(SummaryKind.Session, "session:1", startMs = 30 * MINUTE, endMs = 45 * MINUTE)

    assertTrue(summarizer.canSummarize(book, part))
    val summary = summarizer.summarize(book, part)

    assertEquals(45 * MINUTE, summary.coveredUntilMs)
    assertTrue("Eddie carves a key." in model.prompts.single(), model.prompts.single())
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

private data class Asked(
  val book: String,
  val label: String,
  val parts: List<ServerSummaryPart>,
)

private class FakeApi : ServerTranscriptsApi {

  val asked = mutableListOf<Asked>()
  val combined = mutableListOf<List<String>>()
  var failure: String? = null

  override suspend fun list(server: VoiceServer): ServerResult<List<ServerTranscript>> = ServerResult.Success(emptyList())

  override suspend fun download(
    server: VoiceServer,
    file: String,
    to: File,
  ): ServerResult<Unit> = ServerResult.Success(Unit)

  override suspend fun request(
    server: VoiceServer,
    name: String,
  ): ServerResult<Unit> = ServerResult.Success(Unit)

  override suspend fun summarize(
    server: VoiceServer,
    book: String,
    label: String,
    parts: List<ServerSummaryPart>,
  ): ServerResult<String> {
    asked += Asked(book, label, parts)
    return failure?.let { ServerResult.Failure(it) } ?: ServerResult.Success("From the server.")
  }

  override suspend fun combine(
    server: VoiceServer,
    book: String,
    label: String,
    notes: List<String>,
  ): ServerResult<String> {
    combined += notes
    return ServerResult.Success("All together.")
  }
}

private class FakeModels(private val model: SummaryModel) : SummaryModels {
  override suspend fun model(settings: SummarySettings): SummaryModel = model
}
