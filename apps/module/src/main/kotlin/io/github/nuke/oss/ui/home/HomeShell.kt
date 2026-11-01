package io.github.nuke.oss.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.ContentScale
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.designsystem.NukeContentSurfaceConfig
import io.github.nuke.oss.designsystem.NukeVisualConfig
import io.github.nuke.oss.designsystem.NukeVisualRoot
import io.github.nuke.oss.designsystem.NukeVisualScene
import java.io.File
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.NukeCircularRevealHost
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeIconBadge
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeScreenSurface
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.NukeTopAppBar
import io.github.nuke.oss.designsystem.rememberNukeCircularRevealState
import kotlinx.coroutines.CancellationException

private val ContentWidth = 720.dp

// The shared structure of the module screen and the host switch screen: a home list whose rows open destination
// pages with a circular reveal grown from the touch point. The open destination is a key, so it survives recreation.
@Composable
internal fun HomeShell(
    ui: UiConfig,
    backgroundFile: File?,
    home: @Composable (open: (String, Offset) -> Unit) -> Unit,
    destination: @Composable (key: String, back: (Offset) -> Unit) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val reveal = rememberNukeCircularRevealState(initiallyRevealed = selected != null)
    val clear = { selected = null }
    val back: (Offset) -> Unit = { origin -> reveal.conceal(originInRoot = origin, onFinished = clear) }

    // The system back gesture shrinks the page along with the finger and can be cancelled.
    PredictiveBackHandler(enabled = selected != null) { progress ->
        reveal.beginPredictiveConceal()
        try {
            progress.collect { event -> reveal.seekPredictiveConceal(event.progress) }
            reveal.conceal(onFinished = clear)
        } catch (_: CancellationException) {
            reveal.cancelPredictiveConceal()
        }
    }

    val image by rememberBackgroundImage(backgroundFile, ui.backgroundRevision)
    val visual = NukeVisualConfig(
        hasBackground = image != null,
        backgroundBlurRadius = ui.backgroundBlur,
        scrimAlpha = ui.backgroundScrim,
        content = NukeContentSurfaceConfig(alpha = ui.cardOpacity, blurEnabled = ui.cardBlur),
    )
    val background: @Composable BoxScope.() -> Unit = {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
    NukeVisualRoot(visual, background = background) {
        NukeCircularRevealHost(
            state = reveal,
            backgroundContent = { home { key, origin -> reveal.reveal(origin) { selected = key } } },
            revealedBackgroundContent = { NukeVisualScene(visual, background = background) },
            revealedContent = { selected?.let { destination(it, back) } },
        )
    }
}

// A search that is open clears first: back closes it before anything else.
@Composable
internal fun SearchBackHandler(query: String, onClear: () -> Unit) {
    BackHandler(enabled = query.isNotBlank(), onBack = onClear)
}

// A page of the shell: title bar, then a centred list of groups. A null back is the home page.
@Composable
internal fun HomePage(
    title: String,
    onBack: ((Offset) -> Unit)?,
    onBackPlain: (() -> Unit)? = null,
    backDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    NukeScreenSurface {
        Column(Modifier.fillMaxSize()) {
            NukeTopAppBar(
                title = title,
                onBackClick = onBackPlain,
                onBackClickAt = onBack,
                scrolled = listState.canScrollBackward,
                backDescription = backDescription,
                actions = actions,
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = ContentWidth).fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 18.dp, end = 18.dp,
                        bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

// One tile of the home list: icon badge, title, an optional count, and the key of the page it opens.
internal class HomeTile(
    val title: String,
    val glyph: Glyph,
    val key: String,
    val badge: String? = null,
    val warning: Boolean = false,
)

@Composable
internal fun HomeTileGroup(title: String?, tiles: List<HomeTile>, onOpen: (String, Offset) -> Unit) {
    NukeSettingGroup(title = title) {
        tiles.forEachIndexed { index, tile ->
            val color = if (tile.warning) NukeTheme.colors.danger else NukeTheme.colors.accent
            NukeNavRow(
                title = tile.title,
                leadingIcon = { NukeIconBadge(tile.glyph, color = color) },
                statusText = tile.badge,
                statusColor = if (tile.warning) color else null,
                onClickAt = { origin -> onOpen(tile.key, origin) },
            )
            if (index < tiles.lastIndex) NukeDivider(startPadding = 64.dp)
        }
    }
}

// Tiles are shown three to a group, the first group carrying the section title.
internal fun List<HomeTile>.inGroups(): List<List<HomeTile>> = chunked(3)
