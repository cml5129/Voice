package voice.core.summaries

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OllamaSummaryModelTest {

  private val web = MockWebServer()

  @BeforeTest
  fun start() {
    web.start()
  }

  @AfterTest
  fun stop() {
    web.close()
  }

  private fun model() = OllamaSummaryModel(OkHttpClient(), web.url("/").toString().trimEnd('/'), "qwen3:14b")

  @Test
  fun `asks for one whole answer without thinking`() = runTest {
    web.enqueue(MockResponse(body = """{"model":"qwen3:14b","message":{"role":"assistant","content":"<think>hm</think> Roland walks."},"done":true}"""))

    val answer = model().complete(system = "Summarize.", prompt = "Transcript")

    assertEquals("Roland walks.", answer)
    val request = web.takeRequest()
    assertEquals("/api/chat", request.url.encodedPath)
    val body = request.body?.utf8().orEmpty()
    assertTrue("\"stream\":false" in body, body)
    assertTrue("\"think\":false" in body, body)
    assertTrue("\"num_ctx\":16384" in body, body)
    assertTrue("\"model\":\"qwen3:14b\"" in body, body)
  }

  @Test
  fun `a missing model says so`() = runTest {
    web.enqueue(MockResponse(code = 404, body = """{"error":"model not found"}"""))

    val error = assertFailsWith<SummaryException> { model().complete(system = "Summarize.", prompt = "Transcript") }

    assertEquals("Ollama doesn't have the model qwen3:14b", error.message)
  }
}
