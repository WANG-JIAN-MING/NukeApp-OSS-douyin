package io.github.nuke.oss.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun NukeSegmentedControl(
    selectedIndex: Int,
    options: List<String>,
    onOptionSelected: (Int) -> Unit,
    onOptionLongClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    badgeCounts: List<Int>? = null,
    trackColor: Color = NukeTheme.colors.surface,
    indicatorColor: Color =
        NukeTheme.colors.accent.copy(
            alpha = if (NukeTheme.colors.isLight) 0.12f else 0.22f,
        ),
    selectedTextColor: Color? = null,
    unselectedTextColor: Color? = null,
    elevation: Dp = 0.dp,
    scrollable: Boolean = false,
    scrollableTabWidth: Dp = 120.dp,
) {
    require(options.isNotEmpty()) { "Options must not be empty" }
    val colors = NukeTheme.colors
    val controlAlpha = 1f
    val resolvedTrackColor = trackColor.copy(alpha = trackColor.alpha * controlAlpha)
    val resolvedIndicatorColor = indicatorColor.copy(alpha = indicatorColor.alpha * controlAlpha)
    val safeSelectedIndex = selectedIndex.coerceIn(0, options.lastIndex)
    val resolvedSelectedTextColor =
        selectedTextColor ?: if (colors.isLight) {
            colors.accent.darkenForWhiteContent()
        } else {
            colors.accent
        }
    val resolvedUnselectedTextColor = unselectedTextColor ?: colors.textSecondary
    val selectedBadgeColor = colors.accent.darkenForWhiteContent()
    val unselectedBadgeColor =
        lerp(
            colors.surface,
            colors.textSecondary,
            if (colors.isLight) 0.34f else 0.52f,
        )
    val resolvedSelectedBadgeColor =
        selectedBadgeColor.copy(alpha = selectedBadgeColor.alpha * controlAlpha)
    val resolvedUnselectedBadgeColor =
        unselectedBadgeColor.copy(alpha = unselectedBadgeColor.alpha * controlAlpha)
    BoxWithConstraints(
        modifier =
            modifier
                .height(height)
                .fillMaxWidth()
                .shadow(
                    elevation = elevation,
                    shape = CircleShape,
                    clip = false,
                ).clip(CircleShape)
                .background(resolvedTrackColor)
                .padding(4.dp)
                .selectableGroup(),
    ) {
        val totalWidth = maxWidth
        val requestedItemWidth = if (scrollable) scrollableTabWidth else 0.dp
        val itemWidth =
            if (scrollable && requestedItemWidth * options.size > totalWidth) {
                requestedItemWidth
            } else {
                totalWidth / options.size
            }
        val contentWidth = (itemWidth * options.size).coerceAtLeast(totalWidth)
        val isScrollable = scrollable && contentWidth > totalWidth
        val scrollState = rememberScrollState()
        val density = LocalDensity.current

        LaunchedEffect(safeSelectedIndex, totalWidth, contentWidth, isScrollable) {
            if (!isScrollable) return@LaunchedEffect
            val maxScroll =
                with(density) {
                    (contentWidth - totalWidth).toPx().roundToInt().coerceAtLeast(0)
                }
            val targetScroll =
                with(density) {
                    (
                        itemWidth * safeSelectedIndex +
                            itemWidth / 2 -
                            totalWidth / 2
                    ).coerceIn(0.dp, (contentWidth - totalWidth).coerceAtLeast(0.dp))
                        .toPx()
                        .roundToInt()
                }
            scrollState.animateScrollTo(targetScroll.coerceIn(0, maxScroll))
        }

        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * safeSelectedIndex,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "SegmentedControlIndicatorOffset",
        )

        val stretchProgress = remember { Animatable(0f) }
        var previousSelectedIndex by remember { mutableIntStateOf(safeSelectedIndex) }
        LaunchedEffect(safeSelectedIndex) {
            if (safeSelectedIndex == previousSelectedIndex) return@LaunchedEffect
            previousSelectedIndex = safeSelectedIndex
            stretchProgress.animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(
                        durationMillis = 160,
                        easing = FastOutSlowInEasing,
                    ),
            )
            stretchProgress.animateTo(
                targetValue = 0f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
            )
        }
        val indicatorWidth =
            (itemWidth * (1f + stretchProgress.value * 0.06f))
                .coerceAtMost(contentWidth)
        val stretchInset = (indicatorWidth - itemWidth) / 2
        val stretchedOffset =
            (indicatorOffset - stretchInset).coerceIn(
                minimumValue = 0.dp,
                maximumValue = contentWidth - indicatorWidth,
            )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (isScrollable) {
                            Modifier.horizontalScroll(scrollState)
                        } else {
                            Modifier
                        },
                    ),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(contentWidth)
                        .fillMaxHeight(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = stretchedOffset)
                            .width(indicatorWidth)
                            .fillMaxHeight(1f - stretchProgress.value * 0.10f)
                            .clip(CircleShape)
                            .background(resolvedIndicatorColor),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    options.forEachIndexed { index, title ->
                        val isSelected = index == safeSelectedIndex
                        val badgeCount = badgeCounts?.getOrNull(index) ?: 0

                        val textColor by animateColorAsState(
                            targetValue =
                                if (isSelected) {
                                    resolvedSelectedTextColor
                                } else {
                                    resolvedUnselectedTextColor
                                },
                            animationSpec = tween(durationMillis = 160),
                            label = "SegmentedControlTextColor",
                        )
                        val badgeColor by animateColorAsState(
                            targetValue =
                                if (isSelected) resolvedSelectedBadgeColor
                                else resolvedUnselectedBadgeColor,
                            animationSpec = tween(durationMillis = 160),
                            label = "SegmentedControlBadgeColor",
                        )

                        Box(
                            modifier =
                                Modifier
                                    .then(
                                        if (isScrollable) {
                                            Modifier.width(itemWidth)
                                        } else {
                                            Modifier.weight(1f)
                                        },
                                    ).fillMaxHeight()
                                    .clip(CircleShape)
                                    .semantics {
                                        role = Role.Tab
                                        selected = isSelected
                                    }.nukeClick(
                                        onLongClick =
                                            onOptionLongClick?.let { action ->
                                                { action(index) }
                                            },
                                        onClick = { onOptionSelected(index) },
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = title,
                                    color = textColor,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                if (badgeCount > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier =
                                            Modifier
                                                .clip(CircleShape)
                                                .background(badgeColor)
                                                .widthIn(min = 18.dp)
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = badgeCount.toBadgeLabel(),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Int.toBadgeLabel(): String = if (this > 999) "999+" else toString()

private fun Color.darkenForWhiteContent(): Color {
    var resolved = this
    repeat(8) {
        if (resolved.luminance() <= 0.18f) return resolved
        resolved = lerp(resolved, Color.Black, 0.10f)
    }
    return resolved
}
