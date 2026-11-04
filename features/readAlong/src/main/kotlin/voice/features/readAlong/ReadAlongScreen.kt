@file:OptIn(ExperimentalMaterial3Api::class)

package voice.features.readAlong

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import voice.core.transcript.TranscriptCue
import voice.core.ui.CoverTheme
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@ContributesTo(AppScope::class)
interface Graph {
  val readAlongViewModelFactory: ReadAlongViewModel.Factory
}

@BindingContainer
@ContributesTo(AppScope::class)
object ReadAlongProvider {

  @Provides
  @IntoSet
  fun readAlongNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.ReadAlong> { key ->
    NavEntry(key) {
      ReadAlongScreen(bookId = key.bookId)
    }
  }
}

@Composable
fun ReadAlongScreen(bookId: BookId) {
  val viewModel = retain(bookId.value) {
    rootGraphAs<Graph>().readAlongViewModelFactory.create(bookId)
  }
  val viewState = viewModel.viewState() ?: return
  KeepScreenOn(viewState.playing)
  CoverTheme(cover = viewState.cover) {
    ReadAlongScreen(
      viewState = viewState,
      onClose = viewModel::onCloseClick,
      onCueClick = { cue -> viewModel.onCueClick(viewState.chapterId, cue) },
      onPlayPause = viewModel::playPause,
      onRewind = viewModel::rewind,
      onFastForward = viewModel::fastForward,
    )
  }
}

/**
 * Keeps the screen awake while the book plays, so the lines can be read without touching the phone. Once playback
 * stops, by a pause, the sleep timer or a missed listening check, the screen may turn off again as usual.
 */
@Composable
private fun KeepScreenOn(playing: Boolean) {
  val view = LocalView.current
  DisposableEffect(view, playing) {
    view.keepScreenOn = playing
    onDispose { view.keepScreenOn = false }
  }
}

@Composable
private fun ReadAlongScreen(
  viewState: ReadAlongViewState,
  onClose: () -> Unit,
  onCueClick: (TranscriptCue) -> Unit,
  onPlayPause: () -> Unit,
  onRewind: () -> Unit,
  onFastForward: () -> Unit,
) {
  // follows the voice until the listener scrolls away, and again once they tap a line or "Back to now"
  var following by remember(viewState.chapterId) { mutableStateOf(true) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = viewState.chapterName ?: viewState.bookName,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = if (viewState.chapterName != null) viewState.bookName else stringResource(R.string.read_along_title),
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
      )
    },
    bottomBar = {
      Controls(
        playing = viewState.playing,
        onPlayPause = onPlayPause,
        onRewind = onRewind,
        onFastForward = onFastForward,
      )
    },
    floatingActionButton = {
      if (!following && !viewState.cues.isNullOrEmpty()) {
        ExtendedFloatingActionButton(
          onClick = { following = true },
          text = { Text(stringResource(R.string.read_along_follow)) },
          icon = { Icon(VoiceIcons.KeyboardArrowDown, contentDescription = null) },
        )
      }
    },
  ) { contentPadding ->
    val cues = viewState.cues
    when {
      cues == null -> Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      cues.isEmpty() -> Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(contentPadding)
          .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(stringResource(R.string.read_along_no_transcript), style = MaterialTheme.typography.bodyLarge)
      }
      else -> Lines(
        cues = cues,
        currentIndex = viewState.currentIndex,
        following = following,
        onFollowingChange = { following = it },
        contentPadding = contentPadding,
        onCueClick = { cue ->
          following = true
          onCueClick(cue)
        },
      )
    }
  }
}

@Composable
private fun Lines(
  cues: List<TranscriptCue>,
  currentIndex: Int,
  following: Boolean,
  onFollowingChange: (Boolean) -> Unit,
  contentPadding: PaddingValues,
  onCueClick: (TranscriptCue) -> Unit,
) {
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
  val currentOnFollowingChange by rememberUpdatedState(onFollowingChange)
  LaunchedEffect(listState) {
    listState.interactionSource.interactions.collect { interaction ->
      if (interaction is DragInteraction.Start) currentOnFollowingChange(false)
    }
  }
  LaunchedEffect(currentIndex, following) {
    if (following && currentIndex >= 0) {
      listState.animateScrollToItem(currentIndex)
    }
  }
  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .padding(contentPadding),
  ) {
    // a scroll puts the line being read at the top of the list, which this padding moves to a third of the screen
    val linePosition = maxHeight / 3
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = linePosition, bottom = maxHeight - linePosition),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      itemsIndexed(cues, key = { index, _ -> index }) { index, cue ->
        Line(
          cue = cue,
          current = index == currentIndex,
          read = index < currentIndex,
          onClick = { onCueClick(cue) },
        )
      }
    }
  }
}

@Composable
private fun Line(
  cue: TranscriptCue,
  current: Boolean,
  read: Boolean,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Text(
    text = cue.text.trim(),
    style = MaterialTheme.typography.titleMedium,
    color = when {
      current -> colors.onPrimaryContainer
      read -> colors.onSurface.copy(alpha = 0.55F)
      else -> colors.onSurface
    },
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(if (current) colors.primaryContainer else Color.Transparent)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  )
}

@Composable
private fun Controls(
  playing: Boolean,
  onPlayPause: () -> Unit,
  onRewind: () -> Unit,
  onFastForward: () -> Unit,
) {
  BottomAppBar {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onRewind) {
          Icon(VoiceIcons.FastRewind, contentDescription = stringResource(R.string.playback_action_rewind))
        }
        FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
          Icon(
            imageVector = if (playing) VoiceIcons.Pause else VoiceIcons.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.playback_action_pause else R.string.playback_action_play),
          )
        }
        IconButton(onClick = onFastForward) {
          Icon(VoiceIcons.FastForward, contentDescription = stringResource(R.string.playback_action_fast_forward))
        }
      }
    }
  }
}
