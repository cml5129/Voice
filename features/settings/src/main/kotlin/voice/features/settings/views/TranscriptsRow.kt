@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package voice.features.settings.views

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import voice.core.ui.ShapedIcon
import voice.core.ui.icons.VoiceIcons
import voice.features.settings.SettingsViewState
import voice.core.strings.R as StringsR

/**
 * The transcripts Voice downloaded from the listener's server. A tap looks for new ones right away.
 */
@Composable
internal fun TranscriptsRow(
  viewState: SettingsViewState.TranscriptsViewState,
  onClick: () -> Unit,
) {
  IslandRow(
    title = stringResource(StringsR.string.settings_transcripts_title),
    onClick = onClick,
    leading = {
      ShapedIcon(
        icon = if (viewState.error != null) VoiceIcons.CloudOff else VoiceIcons.DownloadDone,
        shape = MaterialShapes.Circle,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    },
    summary = {
      Text(
        text = when {
          viewState.error != null -> stringResource(StringsR.string.settings_transcripts_failed, viewState.error)
          viewState.downloaded == 0 -> stringResource(StringsR.string.settings_transcripts_none)
          else -> stringResource(StringsR.string.settings_transcripts_summary, viewState.downloaded)
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
  )
}
