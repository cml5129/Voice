package voice.core.sleeptimer

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import voice.core.initializer.AppInitializer
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState.Playing

/**
 * Once playback continues, from the headset, the notification or a shake, the listener picked up where they wanted
 * to, so the offer to rewind is no longer needed.
 */
@ContributesIntoSet(AppScope::class)
class ClearSleepTimerRewindOnPlayback(
  private val playStateManager: PlayStateManager,
  private val sleepTimerRewindStore: SleepTimerRewindStore,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    playStateManager.playStateFlow
      .filter { it == Playing }
      .onEach { sleepTimerRewindStore.updateData { null } }
      .launchIn(scope)
  }
}
