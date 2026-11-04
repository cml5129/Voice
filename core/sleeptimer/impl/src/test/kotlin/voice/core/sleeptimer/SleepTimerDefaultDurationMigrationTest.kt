package voice.core.sleeptimer

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SleepTimerDefaultDurationMigrationTest {

  private fun stored(
    duration: Duration,
    version: Int = 0,
  ) = SleepTimerPreference(
    duration = duration,
    autoSleepTimerEnabled = true,
    autoSleepStartTime = LocalTime.of(22, 0),
    autoSleepEndTime = LocalTime.of(6, 0),
    defaultDurationVersion = version,
  )

  @Test
  fun oldDefaultMovesToNewDefault() {
    assertEquals(stored(30.minutes, version = 1), stored(20.minutes).migrateDefaultDuration())
  }

  @Test
  fun chosenDurationIsKept() {
    assertEquals(stored(45.minutes, version = 1), stored(45.minutes).migrateDefaultDuration())
  }

  @Test
  fun twentyPickedAfterTheMigrationSticks() {
    assertEquals(stored(20.minutes, version = 1), stored(20.minutes, version = 1).migrateDefaultDuration())
  }

  @Test
  fun newInstallsStartAtThirtyMinutes() {
    assertEquals(30.minutes, SleepTimerPreference.Default.duration)
    assertEquals(SleepTimerPreference.Default, SleepTimerPreference.Default.migrateDefaultDuration())
  }
}
