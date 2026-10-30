package voice.core.sleeptimer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import voice.core.initializer.AppInitializer

/**
 * Changing the media volume is a sign the listener is still awake, so it restarts a running countdown and
 * answers the listening check.
 */
@ContributesIntoSet(AppScope::class)
class SleepTimerVolumeObserver(
  private val sleepTimer: SleepTimer,
  private val volumeChanges: VolumeChanges,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    ContextCompat.registerReceiver(
      application,
      object : BroadcastReceiver() {
        override fun onReceive(
          context: Context,
          intent: Intent,
        ) {
          if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
            sleepTimer.onListenerAwake()
            volumeChanges.onChanged()
          }
        }
      },
      IntentFilter(VOLUME_CHANGED_ACTION),
      // sent by the system, which reaches receivers that aren't exported too
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
  }

  private companion object {
    // not part of the public SDK, but sent by every Android version
    const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
  }
}
