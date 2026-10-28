package voice.core.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceServerSettingsTest {

  private val defaults = VoiceServerDefaults(url = "https://example.com/", key = "key")

  @Test
  fun `the build's server is used until another is set`() {
    assertEquals(VoiceServer("https://example.com", "key"), VoiceServerSettings().server(defaults))

    val other = VoiceServerSettings().withServer("https://other.example.com/", "other", defaults)
    assertEquals(VoiceServer("https://other.example.com", "other"), other.server(defaults))
  }

  @Test
  fun `setting the build's own server stores nothing`() {
    val settings = VoiceServerSettings().withServer("https://example.com", " key ", defaults)

    assertEquals(VoiceServerSettings(), settings)
  }

  @Test
  fun `there is no server while switched off or without a key`() {
    assertNull(VoiceServerSettings(enabled = false).server(defaults))
    assertNull(VoiceServerSettings().server(VoiceServerDefaults(url = "https://example.com", key = "")))
  }
}
