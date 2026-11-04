package voice.core.sleeptimer

import kotlinx.serialization.Serializable
import voice.core.common.serialization.LocalTimeSerializer
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Serializable
data class SleepTimerPreference(
  val duration: Duration,
  val autoSleepTimerEnabled: Boolean,
  @Serializable(with = LocalTimeSerializer::class)
  val autoSleepStartTime: LocalTime,
  @Serializable(with = LocalTimeSerializer::class)
  val autoSleepEndTime: LocalTime,
  val listeningCheckEnabled: Boolean = false,
  /** After a missed listening check, asks in the morning whether the book went back to the right place. */
  val morningCheckEnabled: Boolean = false,
  /**
   * Restarts a running countdown when the listener shows they are awake: changing the volume, skipping, or
   * resuming after a pause.
   */
  val restartWhenAwake: Boolean = true,
  /**
   * Which default [duration] the stored one was last brought up to. Version 1 moved the default from 20 to 30
   * minutes, see `SleepTimerDefaultDurationMigration`.
   */
  val defaultDurationVersion: Int = 0,
) {

  companion object {
    val DEFAULT_DURATION: Duration = 30.minutes
    const val CURRENT_DEFAULT_DURATION_VERSION: Int = 1

    val Default: SleepTimerPreference = SleepTimerPreference(
      autoSleepTimerEnabled = false,
      autoSleepStartTime = LocalTime.of(22, 0),
      autoSleepEndTime = LocalTime.of(6, 0),
      duration = DEFAULT_DURATION,
      defaultDurationVersion = CURRENT_DEFAULT_DURATION_VERSION,
    )
  }
}
