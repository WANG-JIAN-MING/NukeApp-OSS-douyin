package io.github.nuke.oss.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

// Jelly click: the target shrinks slightly while pressed and follows the finger from the touch point,
// then springs back to rest on release.
fun Modifier.nukeClick(
    enabled: Boolean = true,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.ContextClick,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val positionedOnClick: (Offset) -> Unit = { onClick() }
    return nukeClickAt(
        enabled = enabled,
        hapticEnabled = hapticEnabled,
        hapticFeedbackType = hapticFeedbackType,
        role = role,
        onLongClick = onLongClick,
        onClick = positionedOnClick
    )
}

fun Modifier.nukeClickAt(
    enabled: Boolean = true,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.ContextClick,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (Offset) -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val resolvedHapticEnabled = hapticEnabled ?: NukeTheme.clickHapticEnabled
    val animationScope = rememberCoroutineScope()

    // Track the component's pixel size and the exact touch point.
    var componentSize by remember { mutableStateOf(IntSize.Zero) }
    var touchOffset by remember { mutableStateOf(Offset.Zero) }
    var hasTouchOffset by remember { mutableStateOf(false) }
    var layoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val pressProgressAnimation = remember { Animatable(0f, 0.001f) }
    val positionAnimation = remember {
        Animatable(
            Offset.Zero,
            Offset.VectorConverter,
            Offset.VisibilityThreshold
        )
    }
    var startPosition by remember { mutableStateOf(Offset.Zero) }

    val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    val releaseMotion = {
        animationScope.launch {
            launch {
                pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
            }
            launch {
                positionAnimation.animateTo(startPosition, positionAnimationSpec)
            }
        }
        Unit
    }

    val handleClick = {
        if (resolvedHapticEnabled) {
            haptic.performHapticFeedback(hapticFeedbackType)
        }
        val localClickOffset = if (hasTouchOffset) {
            touchOffset
        } else {
            Offset(componentSize.width / 2f, componentSize.height / 2f)
        }
        val rootClickOffset = layoutCoordinates
            ?.takeIf { it.isAttached }
            ?.localToRoot(localClickOffset)
            ?: Offset.Zero
        hasTouchOffset = false
        onClick(rootClickOffset)
    }
    val clickModifier = if (onLongClick == null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = handleClick,
        )
    } else {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            hapticFeedbackEnabled = false,
            onLongClick = {
                if (resolvedHapticEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onLongClick()
            },
            onClick = handleClick,
        )
    }

    val gestureModifier = Modifier.pointerInput(enabled, animationScope) {
        if (!enabled) return@pointerInput
        inspectNukeDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                touchOffset = down.position
                hasTouchOffset = true
                animationScope.launch {
                    launch {
                        pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec)
                    }
                    launch {
                        positionAnimation.snapTo(startPosition)
                    }
                }
            },
            onDragEnd = { change ->
                touchOffset = change.position
                releaseMotion()
            },
            onDragCancel = releaseMotion,
        ) { change, _ ->
            touchOffset = change.position
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }

    this
        .onSizeChanged { componentSize = it }
        .onGloballyPositioned { layoutCoordinates = it }
        .graphicsLayer {
            val width = size.width
            val height = size.height
            val progress = pressProgressAnimation.value
            val scale = if (height > 0f) {
                lerp(1f, 1f - 4f.dp.toPx() / height, progress)
            } else {
                1f
            }
            val maxOffset = size.minDimension
            val offset = positionAnimation.value - startPosition

            if (maxOffset > 0f && width > 0f && height > 0f) {
                val initialDerivative = 0.05f
                translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                val maxDragScale = 4f.dp.toPx() / height
                val offsetAngle = atan2(offset.y, offset.x)
                scaleX =
                    scale +
                        maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                        (width / height).fastCoerceAtMost(1f)
                scaleY =
                    scale +
                        maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                        (height / width).fastCoerceAtMost(1f)
            } else {
                scaleX = scale
                scaleY = scale
            }
        }
        .then(clickModifier)
        .then(gestureModifier)
}

private suspend fun PointerInputScope.inspectNukeDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        // Keep the original gesture algorithm, but skip only when a descendant
        // (for example a script-card action button) consumed this down event.
        if (down.isConsumed) return@awaitEachGesture

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = inspectDragOrUp(
            pointerId = initialDown.id,
            onDrag = { onDrag(it, it.positionChange()) },
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.inspectDragOrUp(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.firstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null

    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.firstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.firstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            }
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}
