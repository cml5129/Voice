package voice.core.transcript

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import voice.core.server.VoiceServer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OkHttpServerTranscriptsApiTest {

  private val web = MockWebServer()
  private val api = OkHttpServerTranscriptsApi()

  @BeforeTest
  fun start() {
    web.start()
  }

  @AfterTest
  fun stop() {
    web.close()
  }

  private fun server() = VoiceServer(url = web.url("/").toString().trimEnd('/'), key = "key")

  @Test
  fun `lists the transcripts with the key`() = runTest {
    web.enqueue(MockResponse(body = """[{"name":"Dracula","json":"Dracula.json","lrc":"Dracula.lrc","bytes":5,"updatedAt":"x"}]"""))

    val result = api.list(server())

    assertEquals(ServerResult.Success(listOf(ServerTranscript("Dracula", "Dracula.json", "Dracula.lrc", 5, "x"))), result)
    val request = web.takeRequest()
    assertEquals("/api/transcripts", request.url.encodedPath)
    assertEquals("key", request.headers["X-API-Key"])
  }

  @Test
  fun `downloads one file by its name`() = runTest {
    web.enqueue(MockResponse(body = "[]"))
    val to = Files.createTempDirectory("transcripts").resolve("t.json").toFile()

    val result = api.download(server(), "The Waste Lands [B002V0QK4C].json", to)

    assertEquals(ServerResult.Success(Unit), result)
    assertEquals("[]", to.readText())
    assertEquals(listOf("api", "transcripts", "The Waste Lands [B002V0QK4C].json"), web.takeRequest().url.pathSegments)
  }

  @Test
  fun `asks for a book with the key`() = runTest {
    web.enqueue(MockResponse(code = 202, body = """{"status":"requested"}"""))

    assertEquals(ServerResult.Success(Unit), api.request(server(), "Christine [B00AAA]"))

    val request = web.takeRequest()
    assertEquals("/api/transcripts/requests", request.url.encodedPath)
    assertEquals("POST", request.method)
    assertEquals("key", request.headers["X-API-Key"])
    assertEquals("""{"name":"Christine [B00AAA]"}""", request.body?.utf8())
  }

  @Test
  fun `a server summary comes back as text, a failure with the server's reason`() = runTest {
    web.enqueue(MockResponse(body = """{"summary":"Roland walks.","model":"qwen3:14b"}"""))
    web.enqueue(MockResponse(code = 502, body = """{"error":"The summary model didn't answer"}"""))
    val parts = listOf(ServerSummaryPart(file = "The Waste Lands [B002V0QK4C]", startMs = 0, endMs = 60_000))

    assertEquals(ServerResult.Success("Roland walks."), api.summarize(server(), "The Waste Lands", "One session.", parts))
    assertEquals(
      ServerResult.Failure("The summary model didn't answer"),
      api.summarize(server(), "The Waste Lands", "One session.", parts),
    )

    val body = web.takeRequest().body?.utf8().orEmpty()
    assertTrue("\"startMs\":0" in body, body)
    assertTrue("\"label\":\"One session.\"" in body, body)
  }

  @Test
  fun `notes are sent to be put together`() = runTest {
    web.enqueue(MockResponse(body = """{"summary":"The whole night.","model":"qwen3:14b"}"""))

    val result = api.combine(server(), "The Waste Lands", "Sessions together.", listOf("One.", "Two."))

    assertEquals(ServerResult.Success("The whole night."), result)
    val request = web.takeRequest()
    assertEquals("/api/transcripts/summarize", request.url.encodedPath)
    assertEquals(
      """{"book":"The Waste Lands","label":"Sessions together.","notes":["One.","Two."]}""",
      request.body?.utf8(),
    )
  }

  @Test
  fun `a refused key says so`() = runTest {
    web.enqueue(MockResponse(code = 401))

    assertEquals(ServerResult.Failure("The server didn't accept the key"), api.list(server()))
  }
}
