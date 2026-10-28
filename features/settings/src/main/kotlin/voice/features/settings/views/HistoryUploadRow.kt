@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package voice.features.settings.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import voice.core.ui.ShapedIcon
import voice.core.ui.icons.VoiceIcons
import voice.features.settings.SettingsViewState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import voice.core.strings.R as StringsR

@Composable
internal fun HistoryUploadRow(
  viewState: SettingsViewState.HistoryUploadViewState,
  onEnabledChange: (Boolean) -> Unit,
  onChange: (serverUrl: String, key: String) -> Unit,
) {
  var editing by rememberSaveable { mutableStateOf(false) }
  IslandSwitchRow(
    title = stringResource(StringsR.string.settings_server_title),
    checked = viewState.enabled,
    onCheckedChange = onEnabledChange,
    leading = {
      ShapedIcon(
        icon = VoiceIcons.History,
        shape = MaterialShapes.Cookie7Sided,
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
      )
    },
    summary = {
      Text(
        text = historyUploadSummary(viewState),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
  )
  if (viewState.enabled) {
    IslandRow(
      title = stringResource(StringsR.string.settings_history_upload_server),
      onClick = { editing = true },
      leading = {
        ShapedIcon(
          icon = VoiceIcons.Cloud,
          shape = MaterialShapes.Circle,
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      },
      summary = if (viewState.serverUrl.isNotBlank()) {
        {
          Text(
            text = viewState.serverUrl,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      } else {
        null
      },
    )
  }
  if (editing) {
    HistoryUploadDialog(
      viewState = viewState,
      onDismiss = { editing = false },
      onConfirm = { serverUrl, key ->
        editing = false
        onChange(serverUrl, key)
      },
    )
  }
}

@Composable
private fun historyUploadSummary(viewState: SettingsViewState.HistoryUploadViewState): String {
  val lastUpload = viewState.lastUpload
  return when {
    !viewState.enabled -> stringResource(StringsR.string.settings_server_off)
    !viewState.configured -> stringResource(StringsR.string.settings_history_upload_no_server)
    viewState.error != null -> stringResource(StringsR.string.settings_history_upload_failed, viewState.error)
    lastUpload == null -> stringResource(StringsR.string.settings_history_upload_waiting)
    else -> stringResource(
      StringsR.string.settings_history_upload_last_upload,
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(lastUpload.atZone(ZoneId.systemDefault())),
    )
  }
}

@Composable
private fun HistoryUploadDialog(
  viewState: SettingsViewState.HistoryUploadViewState,
  onDismiss: () -> Unit,
  onConfirm: (serverUrl: String, key: String) -> Unit,
) {
  var serverUrl by rememberSaveable { mutableStateOf(viewState.serverUrl) }
  var key by rememberSaveable { mutableStateOf(viewState.key) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(StringsR.string.settings_server_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(StringsR.string.settings_history_upload_explanation))
        OutlinedTextField(
          value = serverUrl,
          onValueChange = { serverUrl = it },
          label = { Text(stringResource(StringsR.string.settings_history_upload_server)) },
          placeholder = { Text("https://example.com") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
          value = key,
          onValueChange = { key = it },
          label = { Text(stringResource(StringsR.string.settings_history_upload_key)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(serverUrl, key) }) {
        Text(stringResource(StringsR.string.common_action_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}
