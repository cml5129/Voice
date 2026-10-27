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
  /**
   * Restarts a running countdown when the listener shows they are awake: changing the volume, skipping, or
   * resuming after a pause.
   */
  val restartWhenAwake: Boolean = true,
) {

  companion object {
    val Default: SleepTimerPreference = SleepTimerPreference(
      autoSleepTimerEnabled = false,
      autoSleepStartTime = LocalTime.of(22, 0),
      autoSleepEndTime = LocalTime.of(6, 0),
      duration = 20.minutes,
    )
  }
}
