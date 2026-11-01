package voice.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import voice.core.sleeptimer.SleepTimerPreferenceStore

/**
 * The morning check asks with a notification. Turning it on in the settings asks for the permission, but it's on from
 * the start, so without this the permission was never asked for and the question never showed up.
 */
@Composable
internal fun MorningCheckNotificationPrompt(sleepTimerPreferenceStore: SleepTimerPreferenceStore) {
  val context = LocalContext.current
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult = {})
  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= 33) {
      val permission = Manifest.permission.POST_NOTIFICATIONS
      if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
      val preference = sleepTimerPreferenceStore.data.first()
      if (preference.autoSleepTimerEnabled && preference.listeningCheckEnabled && preference.morningCheckEnabled) {
        launcher.launch(permission)
      }
    }
  }
}
