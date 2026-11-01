package io.github.nuke.oss.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun NukeDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    properties: DialogProperties = DialogProperties(),
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var renderDialog by remember { mutableStateOf(visible) }

    LaunchedEffect(visible) {
        if (visible) {
            renderDialog = true
        } else if (renderDialog) {
            delay(NukeDialogExitDurationMillis.toLong().milliseconds)
            renderDialog = false
        }
    }

    if (!renderDialog) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        NukeDialogSurface(
            modifier = modifier,
            visible = visible,
            title = title,
            actions = actions,
            content = content
        )
    }
}

@Composable
fun NukeDialog(
    onDismissRequest: () -> Unit,
    title: String,
    contentText: String,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissButtonText: String? = null,
    properties: DialogProperties = DialogProperties()
) {
    NukeDialog(
        visible = true,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        properties = properties,
        actions = {
            if (dismissButtonText != null) {
                NukeDialogTextButton(
                    text = dismissButtonText,
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                )
            }
            NukeDialogAccentButton(
                text = confirmButtonText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f)
            )
        }
    ) {
        NukeDialogBodyText(
            text = contentText,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun NukeDialogSurface(
    modifier: Modifier,
    visible: Boolean,
    title: String?,
    actions: (@Composable RowScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    var animateIn by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        animateIn = visible
    }

    val dialogScaleX by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.94f,
        animationSpec = if (animateIn) {
            keyframes { durationMillis = 300; 0.94f at 0; 1.025f at 110; 0.995f at 210; 1f at 300 }
        } else {
            keyframes { durationMillis = NukeDialogExitDurationMillis; 1f at 0; 1.012f at 45; 0.94f at NukeDialogExitDurationMillis }
        }, label = "NukeDialogScaleX"
    )
    val dialogScaleY by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.92f,
        animationSpec = if (animateIn) {
            keyframes { durationMillis = 300; 0.92f at 0; 1.04f at 110; 0.99f at 210; 1f at 300 }
        } else {
            keyframes { durationMillis = NukeDialogExitDurationMillis; 1f at 0; 1.018f at 45; 0.92f at NukeDialogExitDurationMillis }
        }, label = "NukeDialogScaleY"
    )
    val alpha by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = if (animateIn) {
            keyframes { durationMillis = 160; 0f at 0; 1f at 80; 1f at 160 }
        } else {
            tween(durationMillis = NukeDialogExitDurationMillis)
        }, label = "NukeDialogAlpha"
    )
    val fluidEdgeColor by animateColorAsState(
        targetValue = if (animateIn) NukeTheme.colors.textSecondary.copy(alpha = 0.045f) else NukeTheme.colors.accent.copy(alpha = 0.16f),
        animationSpec = tween(durationMillis = 180), label = "NukeDialogFluidEdgeColor"
    )
    val fluidEdgePadding by animateDpAsState(
        targetValue = if (animateIn) 1.dp else 2.dp,
        animationSpec = tween(durationMillis = 180), label = "NukeDialogFluidEdgePadding"
    )

    val shape = NukeSquircleShape(22.dp)

    NukeFluidSurface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = dialogScaleX
                scaleY = dialogScaleY
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            },
        shape = shape,
        edgeColor = fluidEdgeColor,
        edgeThickness = fluidEdgePadding,
        contentPadding = PaddingValues(20.dp)
    ) {
        Column {
            if (title != null) {
                BasicText(
                    text = title,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = TextStyle(
                        color = NukeTheme.colors.textPrimary,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            content()

            if (actions != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }
    }
}

private const val NukeDialogExitDurationMillis = 150

@Composable
fun NukeDialogBodyText(
    text: String,
    modifier: Modifier = Modifier
) {
    BasicText(
        modifier = modifier.fillMaxWidth(),
        text = text,
        style = TextStyle(
            color = NukeTheme.colors.textSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    )
}
