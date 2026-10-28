package voice.core.historyupload

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
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.initializer.AppInitializer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Uploads the history a little while after something new happened, and keeps trying while the server can't be
 * reached. What doesn't make it stays on the device and goes up after the next event or app start.
 */
@ContributesIntoSet(AppScope::class)
@Inject
public class HistoryUploadInitializer(
  private val uploader: HistoryUploader,
  private val store: HistoryUploadStore,
  private val historyRepo: ListeningHistoryRepo,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    scope.launch {
      val server = store.data.map { it.serverUrl to it.key }.distinctUntilChanged()
      combine(historyRepo.newestId(), server) { newestId, _ -> newestId }
        .collectLatest {
          // skipping ahead a few times goes up as one request
          delay(UPLOAD_DELAY)
          var retryIn = FIRST_RETRY
          while (!uploader.upload()) {
            delay(retryIn)
            retryIn = (retryIn * 2).coerceAtMost(LAST_RETRY)
          }
        }
    }
  }

  private companion object {
    val UPLOAD_DELAY = 30.seconds
    val FIRST_RETRY = 1.minutes
    val LAST_RETRY = 30.minutes
  }
}
