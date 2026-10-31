@file:OptIn(ExperimentalMaterial3Api::class)

package voice.features.summaries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.strings.R
import voice.core.summaries.SummaryKind
import voice.core.summaries.SummaryPart
import voice.core.summaries.SummaryProvider
import voice.core.summaries.SummarySettings
import voice.core.ui.CoverTheme
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@ContributesTo(AppScope::class)
interface Graph {
  val summariesViewModelFactory: SummariesViewModel.Factory
}

@BindingContainer
@ContributesTo(AppScope::class)
object SummariesProvider {

  @Provides
  @IntoSet
  fun summariesNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Summaries> { key ->
    NavEntry(key) {
      SummariesScreen(bookId = key.bookId)
    }
  }
}

@Composable
fun SummariesScreen(bookId: BookId) {
  val viewModel = retain(bookId.value) {
    rootGraphAs<Graph>().summariesViewModelFactory.create(bookId)
  }
  val viewState = viewModel.viewState() ?: return
  CoverTheme(cover = viewState.cover) {
    SummariesScreen(
      viewState = viewState,
      onClose = viewModel::onCloseClick,
      onTabChange = viewModel::onTabChange,
      onSummarize = viewModel::onSummarize,
      onModelClick = viewModel::onModelClick,
    )
    if (viewState.editingModel) {
      ModelDialog(
        settings = viewState.settings,
        onDismiss = viewModel::onModelDismiss,
        onSave = viewModel::onModelSave,
      )
    }
  }
}

private val tabs = listOf(SummaryKind.Chapter, SummaryKind.HalfHour, SummaryKind.Session)

@Composable
private fun SummariesScreen(
  viewState: SummariesViewState,
  onClose: () -> Unit,
  onTabChange: (SummaryKind) -> Unit,
  onSummarize: (SummaryPart) -> Unit,
  onModelClick: () -> Unit,
) {
  Scaffold(
    topBar = {
      Column {
        TopAppBar(
          title = {
            Column {
              Text(stringResource(R.string.summaries_title))
              Text(
                text = viewState.bookName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          },
          navigationIcon = {
            IconButton(onClick = onClose) {
              Icon(VoiceIcons.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
            }
          },
          actions = {
            TextButton(onClick = onModelClick) {
              Text(providerName(viewState.settings.provider))
            }
          },
        )
        PrimaryTabRow(selectedTabIndex = tabs.indexOf(viewState.tab)) {
          tabs.forEach { kind ->
            Tab(
              selected = kind == viewState.tab,
              onClick = { onTabChange(kind) },
              text = { Text(stringResource(kind.label())) },
            )
          }
        }
      }
    },
  ) { contentPadding ->
    when {
      !viewState.hasTranscript -> Message(stringResource(R.string.summaries_no_transcript), contentPadding)
      !viewState.settings.configured -> Message(
        text = stringResource(R.string.summaries_setup),
        contentPadding = contentPadding,
        action = stringResource(R.string.summaries_model_title) to onModelClick,
      )
      viewState.parts.isEmpty() -> Message(stringResource(R.string.summaries_sessions_none), contentPadding)
      else -> PartList(viewState, contentPadding, onSummarize)
    }
  }
}

@Composable
private fun PartList(
  viewState: SummariesViewState,
  contentPadding: PaddingValues,
  onSummarize: (SummaryPart) -> Unit,
) {
  val listState = rememberLazyListState()
  // opens at the part playing now, not at the start of a long book
  LaunchedEffect(viewState.tab) {
    val index = viewState.currentIndex
    if (index != null) listState.scrollToItem(index)
  }
  LazyColumn(
    state = listState,
    contentPadding = PaddingValues(
      start = 16.dp,
      end = 16.dp,
      top = contentPadding.calculateTopPadding() + 12.dp,
      bottom = contentPadding.calculateBottomPadding() + 24.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(viewState.parts, key = { it.part.key }) { part ->
      PartCard(part, onSummarize = { onSummarize(part.part) })
    }
  }
}

@Composable
private fun PartCard(
  part: SummaryPartViewState,
  onSummarize: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (part.title != null) {
        Text(part.title, style = MaterialTheme.typography.titleMedium)
      }
      Text(
        text = part.range,
        style = if (part.title == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        color = if (part.title == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      when {
        part.summary != null -> Text(part.summary, style = MaterialTheme.typography.bodyMedium)
        !part.heard -> Text(
          text = stringResource(R.string.summaries_not_heard),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (part.working) {
        Text(stringResource(R.string.summaries_working), style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      if (part.error != null) {
        Text(
          text = stringResource(R.string.summaries_failed, part.error),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
      if (part.canSummarize) {
        TextButton(onClick = onSummarize, modifier = Modifier.align(Alignment.End)) {
          Text(stringResource(if (part.summary == null) R.string.summaries_action_summarize else R.string.summaries_action_update))
        }
      }
    }
  }
}

@Composable
private fun Message(
  text: String,
  contentPadding: PaddingValues,
  action: Pair<String, () -> Unit>? = null,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(contentPadding)
      .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(text, style = MaterialTheme.typography.bodyLarge)
      if (action != null) {
        TextButton(onClick = action.second) {
          Text(action.first)
        }
      }
    }
  }
}

@Composable
private fun ModelDialog(
  settings: SummarySettings,
  onDismiss: () -> Unit,
  onSave: (SummarySettings) -> Unit,
) {
  var provider by rememberSaveable { mutableStateOf(settings.provider) }
  var claudeKey by rememberSaveable { mutableStateOf(settings.claudeKey) }
  var ollamaUrl by rememberSaveable { mutableStateOf(settings.ollamaUrl) }
  var ollamaModel by rememberSaveable { mutableStateOf(settings.ollamaModel) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.summaries_model_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.summaries_model_explanation))
        Column(Modifier.selectableGroup()) {
          SummaryProvider.entries.forEach { option ->
            ProviderRow(
              name = providerName(option),
              selected = option == provider,
              onClick = { provider = option },
            )
          }
        }
        if (provider == SummaryProvider.Ollama) {
          OutlinedTextField(
            value = ollamaUrl,
            onValueChange = { ollamaUrl = it },
            label = { Text(stringResource(R.string.summaries_model_ollama_url)) },
            placeholder = { Text("http://192.168.1.20:11434") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          )
          OutlinedTextField(
            value = ollamaModel,
            onValueChange = { ollamaModel = it },
            label = { Text(stringResource(R.string.summaries_model_ollama_model)) },
            singleLine = true,
          )
        } else {
          OutlinedTextField(
            value = claudeKey,
            onValueChange = { claudeKey = it },
            label = { Text(stringResource(R.string.summaries_model_claude_key)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(
            settings.copy(
              provider = provider,
              claudeKey = claudeKey.trim(),
              ollamaUrl = ollamaUrl.trim().trimEnd('/'),
              ollamaModel = ollamaModel.trim(),
            ),
          )
        },
      ) {
        Text(stringResource(R.string.common_action_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.common_dialog_cancel))
      }
    },
  )
}

@Composable
private fun ProviderRow(
  name: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Text(name, modifier = Modifier.padding(start = 12.dp))
  }
}

// product names, the same in every language
private fun providerName(provider: SummaryProvider): String = when (provider) {
  SummaryProvider.Haiku -> "Claude Haiku"
  SummaryProvider.Sonnet -> "Claude Sonnet"
  SummaryProvider.Ollama -> "Ollama"
}

private fun SummaryKind.label(): Int = when (this) {
  SummaryKind.Chapter -> R.string.playback_chapters_title
  SummaryKind.HalfHour -> R.string.summaries_tab_half_hours
  SummaryKind.Session -> R.string.summaries_tab_sessions
}
