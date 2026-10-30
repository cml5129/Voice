package voice.core.transcript

import voice.core.data.ChapterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptFilesTest {

  @Test
  fun `the file name comes from a document id`() {
    val id = ChapterId(
      "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks/document/" +
        "primary%3AAudiobooks%2FThe%20Waste%20Lands%20%5BB002V0QK4C%5D%2FThe%20Waste%20Lands%20%5BB002V0QK4C%5D.m4b",
    )

    assertEquals("The Waste Lands [B002V0QK4C].m4b", audioFileName(id))
  }

  @Test
  fun `the file name comes from a file path`() {
    assertEquals("11+22+63 [B005NTW2GK].m4b", audioFileName(ChapterId("file:///storage/Books/11%2B22%2B63%20%5BB005NTW2GK%5D.m4b")))
    assertEquals("Book 1.mp3", audioFileName(ChapterId("/storage/emulated/0/Books/Book 1.mp3")))
  }

  @Test
  fun `no file name without one in the uri`() {
    assertNull(audioFileName(ChapterId("https://audiobookshelf.example.com/api/items/123")))
  }

  @Test
  fun `names match without extension, case and punctuation`() {
    assertEquals(transcriptMatchKey("The Waste Lands [B002V0QK4C]"), transcriptMatchKey("The Waste Lands [B002V0QK4C].m4b"))
    assertEquals(transcriptMatchKey("the_waste_lands_B002V0QK4C.m4b"), transcriptMatchKey("The Waste Lands [B002V0QK4C].m4b"))
  }
}
