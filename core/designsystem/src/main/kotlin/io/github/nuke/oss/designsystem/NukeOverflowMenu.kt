package io.github.nuke.oss.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

class NukeMenuItem(val title: String, val onClick: () -> Unit)

// A top bar button that opens a short list of actions, placed like the select menu. Choosing an item closes the
// menu before the action runs.
@Composable
fun NukeOverflowMenu(items: List<NukeMenuItem>, description: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val expanded = remember { MutableTransitionState(false) }
    var opensAbove by remember { mutableStateOf(false) }
    Box(modifier) {
        NukeBarButton(onClick = { expanded.targetState = true }) { GlyphIcon(Glyph.More, description) }
        if (expanded.targetState || expanded.currentState) {
            val position = remember(density) {
                NukeSelectPositionProvider(
                    menuOverlapPx = with(density) { 18.dp.roundToPx() },
                    windowMarginPx = with(density) { 12.dp.roundToPx() },
                    onPlacementChanged = { opensAbove = it },
                )
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { expanded.targetState = false },
                properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
            ) {
                AnimatedVisibility(
                    visibleState = expanded,
                    enter = fadeIn(tween(90)) + scaleIn(tween(120), initialScale = 0.94f, transformOrigin = menuTransformOrigin(opensAbove)),
                    exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.96f, transformOrigin = menuTransformOrigin(opensAbove)),
                ) {
                    NukeFluidSurface(
                        modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp, max = 280.dp),
                        shape = NukeSquircleShape(14.dp),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        Column {
                            items.forEach { item ->
                                BasicText(
                                    text = item.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .clip(NukeSquircleShape(10.dp))
                                        .nukeClick(role = Role.Button) {
                                            expanded.targetState = false
                                            item.onClick()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Normal),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
