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
  fun `a refused key says so`() = runTest {
    web.enqueue(MockResponse(code = 401))

    assertEquals(ServerResult.Failure("The server didn't accept the key"), api.list(server()))
  }
}
