package voice.core.transcript

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import voice.core.data.repo.BookRepository
import voice.core.initializer.AppInitializer
import voice.core.server.VoiceServerConfig
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Looks for new transcripts when the app starts, when the server or the library changes, and every few hours while
 * the app runs, because the server finishes a book every hour or so while it works through the list.
 */
@ContributesIntoSet(AppScope::class)
@Inject
public class TranscriptDownloadInitializer(
  private val downloader: TranscriptDownloader,
  private val serverConfig: VoiceServerConfig,
  private val bookRepository: BookRepository,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    scope.launch {
      val chapters = bookRepository.flow()
        .map { books -> books.flatMap { book -> book.chapters.map { it.id } }.toSet() }
        .distinctUntilChanged()
      combine(chapters, serverConfig.server) { chapterIds, server -> chapterIds to server }
        .collectLatest { (_, server) ->
          if (server == null) return@collectLatest
          // a library scan changes the books many times in a row
          delay(START_DELAY)
          var retryIn = FIRST_RETRY
          while (true) {
            if (downloader.download()) {
              retryIn = FIRST_RETRY
              delay(CHECK_EVERY)
            } else {
              delay(retryIn)
              retryIn = (retryIn * 2).coerceAtMost(CHECK_EVERY)
            }
          }
        }
    }
  }

  private companion object {
    val START_DELAY = 10.seconds
    val FIRST_RETRY = 1.minutes
    val CHECK_EVERY = 3.hours
  }
}
