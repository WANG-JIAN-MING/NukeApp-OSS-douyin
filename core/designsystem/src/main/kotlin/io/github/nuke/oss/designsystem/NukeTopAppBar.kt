package io.github.nuke.oss.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NukeTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null, // null hides the back button (home screen)
    actions: @Composable RowScope.() -> Unit = {}, // trailing actions slot
    onBackClickAt: ((Offset) -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    // Shows the divider once content scrolls under the bar. The design system derives this from a Material scroll
    // behavior; that API is experimental, so the screen passes the list state instead.
    scrolled: Boolean = false,
    backDescription: String? = null,
) {
    val dividerVisible = scrolled
    val dividerAlpha by animateFloatAsState(
        targetValue = if (dividerVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "TopAppBarDividerAlpha"
    )
    val titleOffset by animateDpAsState(
        targetValue = if (dividerVisible) 0.dp else 2.dp,
        animationSpec = tween(durationMillis = 160),
        label = "TopAppBarTitleOffset"
    )
    val titleScale by animateFloatAsState(
        targetValue = if (dividerVisible) 0.965f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "TopAppBarTitleScale"
    )
    val resolvedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(durationMillis = 160),
        label = "TopAppBarContainerColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(resolvedContainerColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (onBackClick != null || onBackClickAt != null) {
                    NukeTopAppBarBackButton(
                        onClick = onBackClick,
                        onClickAt = onBackClickAt,
                        description = backDescription,
                    )
                }
            }

            BasicText(
                modifier = Modifier.graphicsLayer {
                    translationY = titleOffset.toPx()
                    scaleX = titleScale
                    scaleY = titleScale
                },
                text = title,
                style = TextStyle(
                    color = NukeTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .graphicsLayer { alpha = dividerAlpha }
                .background(NukeTheme.colors.border)
        )
    }
}

@Composable
private fun NukeTopAppBarBackButton(
    onClick: (() -> Unit)?,
    onClickAt: ((Offset) -> Unit)?,
    description: String?,
) {
    val clickModifier = when {
        onClickAt != null -> Modifier.nukeClickAt(role = Role.Button, onClick = onClickAt)
        onClick != null -> Modifier.nukeClick(role = Role.Button, onClick = onClick)
        else -> Modifier
    }

    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .then(clickModifier)
            .padding(10.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        NukeTopAppBarBackIcon(
            color = NukeTheme.colors.textPrimary
        )
    }
}

@Composable
private fun NukeTopAppBarBackIcon(
    modifier: Modifier = Modifier,
    color: Color
) {
    Canvas(modifier = modifier.size(18.dp).padding(horizontal = 2.dp)) {
        val strokeWidth = 2.dp.toPx()
        val start = Offset(size.width * 0.66f, size.height * 0.24f)
        val center = Offset(size.width * 0.34f, size.height * 0.5f)
        val end = Offset(size.width * 0.66f, size.height * 0.76f)

        drawLine(
            color = color,
            start = start,
            end = center,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = center,
            end = end,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}
