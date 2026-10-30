package voice.core.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceServerSettingsTest {

  @Test
  fun `a server needs an address and a key`() {
    val settings = VoiceServerSettings().withServer(" https://example.com/ ", " key ")

    assertEquals(VoiceServer("https://example.com", "key"), settings.server())
    assertNull(VoiceServerSettings().withServer("https://example.com", "").server())
    assertNull(VoiceServerSettings().server())
  }

  @Test
  fun `there is no server while switched off`() {
    val settings = VoiceServerSettings().withServer("https://example.com", "key")

    assertNull(settings.copy(enabled = false).server())
  }
}
