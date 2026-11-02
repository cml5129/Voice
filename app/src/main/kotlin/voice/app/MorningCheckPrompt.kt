package voice.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import voice.core.data.ListeningEvent
import voice.core.sleeptimer.MorningCheck
import voice.core.ui.icons.VoiceIcons
import voice.core.strings.R as StringsR

/**
 * Asks the morning question in the app as well, as the notification is easy to miss. It stays until it's answered,
 * dismissed, or the day is over.
 */
@Composable
internal fun MorningCheckPrompt(morningCheck: MorningCheck) {
  // opening the app again, maybe the next morning, asks about the night that just ended
  var resumed by remember { mutableIntStateOf(0) }
  LifecycleResumeEffect(Unit) {
    resumed++
    onPauseOrDispose {}
  }
  val question = remember(resumed) { morningCheck.question() }.collectAsState(initial = null).value ?: return
  val scope = rememberCoroutineScope()
  AlertDialog(
    // a stray tap next to the dialog shouldn't throw the question away
    properties = DialogProperties(dismissOnClickOutside = false),
    onDismissRequest = { scope.launch { morningCheck.dismiss() } },
    icon = {
      Icon(imageVector = VoiceIcons.Bedtime, contentDescription = null)
    },
    title = {
      Text(stringResource(StringsR.string.morning_check_title))
    },
    text = {
      Text("${question.bookName} · ${question.place}")
    },
    confirmButton = {
      TextButton(onClick = { scope.launch { morningCheck.onAnswer(question.spot, right = true, ListeningEvent.Source.App) } }) {
        Text(stringResource(StringsR.string.morning_check_right))
      }
    },
    dismissButton = {
      TextButton(onClick = { scope.launch { morningCheck.onAnswer(question.spot, right = false, ListeningEvent.Source.App) } }) {
        Text(stringResource(StringsR.string.morning_check_wrong))
      }
    },
  )
}
