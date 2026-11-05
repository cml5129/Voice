package voice.core.sleeptimer

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import voice.core.initializer.AppInitializer
import voice.core.sleeptimer.SleepTimerPreference.Companion.CURRENT_DEFAULT_DURATION_VERSION
import kotlin.time.Duration.Companion.minutes

/**
 * The sleep timer's default went from 20 to 30 minutes. A stored duration still at the old default of 20 minutes
 * moves to the new one, once, so picking 20 again later sticks.
 */
@ContributesIntoSet(AppScope::class)
class SleepTimerDefaultDurationMigration(
  private val sleepTimerPreferenceStore: SleepTimerPreferenceStore,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    scope.launch {
      sleepTimerPreferenceStore.updateData { it.migrateDefaultDuration() }
    }
  }
}

internal fun SleepTimerPreference.migrateDefaultDuration(): SleepTimerPreference {
  if (defaultDurationVersion >= CURRENT_DEFAULT_DURATION_VERSION) return this
  return copy(
    duration = if (duration == 20.minutes) SleepTimerPreference.DEFAULT_DURATION else duration,
    defaultDurationVersion = CURRENT_DEFAULT_DURATION_VERSION,
  )
}
