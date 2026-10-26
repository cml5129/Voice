package voice.features.playbackScreen.sleepTimerRewind

import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookMetadata
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.sleeptimer.SleepTimerRewind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class SleepTimerRewindOptionsTest {

  @Test
  fun `30 minute timer offers five minute steps`() {
    assertEquals(
      expected = listOf(30, 25, 20, 15, 10, 5).map { it.minutes },
      actual = rewind(timerDuration = 30.minutes).rewindOptions().map { it.amount },
    )
  }

  @Test
  fun `long timer offers at most six evenly spaced options`() {
    assertEquals(
      expected = listOf(60, 50, 40, 30, 20, 10).map { it.minutes },
      actual = rewind(timerDuration = 60.minutes).rewindOptions().map { it.amount },
    )
    assertEquals(
      expected = listOf(45, 40, 30, 20, 10).map { it.minutes },
      actual = rewind(timerDuration = 45.minutes).rewindOptions().map { it.amount },
    )
  }

  @Test
  fun `short timer only offers the timer start`() {
    assertEquals(
      expected = listOf(3.minutes),
      actual = rewind(timerDuration = 3.minutes).rewindOptions().map { it.amount },
    )
  }

  @Test
  fun `only the full amount is the timer start`() {
    val options = rewind(timerDuration = 10.minutes).rewindOptions()
    assertEquals(expected = listOf(true, false), actual = options.map { it.isTimerStart })
  }

  @Test
  fun `positions count forward from the timer start at playback speed`() {
    val start = 60.minutes.inWholeMilliseconds
    val options = rewind(timerDuration = 30.minutes, startPositionInBookMs = start, playbackSpeed = 1.5F)
      .rewindOptions()
      .associate { it.amount to it.positionInBookMs }

    assertEquals(expected = start, actual = options[30.minutes])
    // 20 timer minutes at 1.5x are 30 minutes of the book
    assertEquals(expected = start + 30.minutes.inWholeMilliseconds, actual = options[10.minutes])
  }

  @Test
  fun `book position maps to chapter position`() {
    val book = book(chapterDurations = listOf(10.minutes, 20.minutes, 5.minutes))

    assertEquals(
      expected = ChapterPosition(book.chapters[0].id, 0),
      actual = book.chapterPosition(0),
    )
    assertEquals(
      expected = ChapterPosition(book.chapters[1].id, 5.minutes.inWholeMilliseconds),
      actual = book.chapterPosition(15.minutes.inWholeMilliseconds),
    )
    assertEquals(
      expected = ChapterPosition(book.chapters[2].id, 0),
      actual = book.chapterPosition(30.minutes.inWholeMilliseconds),
    )
    // past the end stays at the end of the last chapter
    assertEquals(
      expected = ChapterPosition(book.chapters[2].id, 5.minutes.inWholeMilliseconds),
      actual = book.chapterPosition(40.minutes.inWholeMilliseconds),
    )
  }

  private fun rewind(
    timerDuration: Duration,
    startPositionInBookMs: Long = 0,
    playbackSpeed: Float = 1F,
  ) = SleepTimerRewind(
    bookId = BookId("book"),
    startPositionInBookMs = startPositionInBookMs,
    timerDuration = timerDuration,
    playbackSpeed = playbackSpeed,
  )

  private fun book(chapterDurations: List<Duration>): Book {
    val chapters = chapterDurations.map { duration ->
      Chapter(
        id = ChapterId(Uuid.random().toString()),
        name = "chapter",
        duration = duration.inWholeMilliseconds,
        fileLastModified = Instant.EPOCH,
        markData = emptyList(),
        fileSize = 0,
      )
    }
    return Book(
      content = BookContent(
        positionInChapter = 0,
        playbackSpeed = 1F,
        addedAt = Instant.EPOCH,
        chapters = chapters.map { it.id },
        currentChapter = chapters.first().id,
        isActive = true,
        lastPlayedAt = Instant.EPOCH,
        skipSilence = false,
        id = BookId(Uuid.random().toString()),
        gain = 0F,
        metadata = BookMetadata(
          name = "book",
          author = null,
          album = null,
          title = null,
          genre = null,
          narrator = null,
          series = null,
          part = null,
          cover = null,
          fileLastModified = null,
        ),
        customName = null,
        customCover = null,
      ),
      chapters = chapters,
    )
  }
}
