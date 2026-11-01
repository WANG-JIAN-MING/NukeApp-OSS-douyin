package io.github.nuke.oss.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

private const val CircularRevealStiffness = 170f
private const val RevealInputUnlockProgress = 0.999f
private const val ConcealCompletionProgress = 0.005f

private fun circularRevealSpring() = spring<Float>(
    dampingRatio = 0.68f,
    stiffness = CircularRevealStiffness
)

private enum class NukeCircularRevealTransition {
    Revealing,
    Concealing,
    PredictiveConcealing
}

class NukeCircularRevealShape(
    private val center: Offset,
    private val progress: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        if (progress <= 0f) return Outline.Generic(Path())

        val maxWidth = max(center.x, size.width - center.x)
        val maxHeight = max(center.y, size.height - center.y)
        val currentRadius = hypot(maxWidth, maxHeight) * progress
        val path = Path().apply {
            addOval(Rect(center = center, radius = currentRadius))
        }
        return Outline.Generic(path)
    }
}

@Stable
class NukeCircularRevealState internal constructor(
    private val coroutineScope: CoroutineScope,
    initiallyRevealed: Boolean
) {
    private val progressAnimation = Animatable(if (initiallyRevealed) 1f else 0f)
    private var openingOriginInRoot = Offset.Zero
    private var transitionJob: Job? = null
    private var transitionGeneration = 0L
    private var runningTransition: NukeCircularRevealTransition? = null
    private var predictiveStartProgress = 1f

    internal var revealOriginInRoot by mutableStateOf(Offset.Zero)
        private set

    internal var isContentVisible by mutableStateOf(initiallyRevealed)
        private set

    var isTransitionRunning by mutableStateOf(false)
        private set

    internal var isInputBlocked by mutableStateOf(false)
        private set

    val isRevealed: Boolean
        get() = isContentVisible

    internal val progress: Float
        get() = progressAnimation.value

    fun reveal(
        originInRoot: Offset,
        prepareContent: () -> Unit
    ) {
        if (
            isContentVisible ||
            runningTransition == NukeCircularRevealTransition.Revealing
        ) {
            return
        }

        openingOriginInRoot = originInRoot
        revealOriginInRoot = originInRoot

        launchTransition(NukeCircularRevealTransition.Revealing) { generation ->
            progressAnimation.snapTo(0f)
            prepareContent()
            isContentVisible = true
            withFrameNanos { }
            animateToRevealed(generation)
        }
    }

    fun beginPredictiveConceal() {
        if (
            !isContentVisible ||
            runningTransition == NukeCircularRevealTransition.PredictiveConcealing
        ) {
            return
        }

        transitionGeneration++
        transitionJob?.cancel()
        transitionJob = null
        runningTransition = NukeCircularRevealTransition.PredictiveConcealing
        isTransitionRunning = true
        isInputBlocked = true
        revealOriginInRoot = openingOriginInRoot
        predictiveStartProgress = progressAnimation.value.coerceIn(0f, 1f)
    }

    suspend fun seekPredictiveConceal(gestureProgress: Float) {
        if (runningTransition != NukeCircularRevealTransition.PredictiveConcealing) return

        val remainingProgress = 1f - gestureProgress.coerceIn(0f, 1f)
        progressAnimation.snapTo(predictiveStartProgress * remainingProgress)
    }

    fun cancelPredictiveConceal() {
        if (runningTransition != NukeCircularRevealTransition.PredictiveConcealing) return

        launchTransition(NukeCircularRevealTransition.Revealing) { generation ->
            animateToRevealed(generation)
        }
    }

    fun conceal(
        originInRoot: Offset? = null,
        onFinished: () -> Unit = {}
    ) {
        if (
            !isContentVisible ||
            runningTransition == NukeCircularRevealTransition.Concealing
        ) {
            return
        }

        revealOriginInRoot = originInRoot ?: openingOriginInRoot

        launchTransition(NukeCircularRevealTransition.Concealing) { generation ->
            animateUntilConcealed()
            if (transitionGeneration != generation) return@launchTransition

            progressAnimation.snapTo(0f)
            isContentVisible = false
            onFinished()
        }
    }

    suspend fun snapToRevealed(originInRoot: Offset = Offset.Zero) {
        if (isContentVisible && !isTransitionRunning) return

        transitionGeneration++
        transitionJob?.cancel()
        transitionJob = null
        runningTransition = null
        isTransitionRunning = false
        isInputBlocked = false

        openingOriginInRoot = originInRoot
        revealOriginInRoot = originInRoot
        progressAnimation.snapTo(1f)
        isContentVisible = true
    }

    private fun launchTransition(
        transition: NukeCircularRevealTransition,
        block: suspend (generation: Long) -> Unit
    ) {
        val generation = ++transitionGeneration
        transitionJob?.cancel()
        runningTransition = transition
        isTransitionRunning = true
        isInputBlocked = true

        transitionJob = coroutineScope.launch {
            try {
                block(generation)
            } finally {
                if (transitionGeneration == generation) {
                    transitionJob = null
                    runningTransition = null
                    isTransitionRunning = false
                    isInputBlocked = false
                }
            }
        }
    }

    private suspend fun animateUntilConcealed() = coroutineScope {
        val animationJob = launch {
            progressAnimation.animateTo(
                targetValue = 0f,
                animationSpec = circularRevealSpring()
            )
        }

        while (
            animationJob.isActive &&
            progressAnimation.value > ConcealCompletionProgress
        ) {
            withFrameNanos { }
        }
        animationJob.cancelAndJoin()
    }

    private suspend fun animateToRevealed(generation: Long) {
        progressAnimation.animateTo(
            targetValue = 1f,
            animationSpec = circularRevealSpring()
        ) {
            if (
                transitionGeneration == generation &&
                value >= RevealInputUnlockProgress
            ) {
                isInputBlocked = false
            }
        }
        progressAnimation.snapTo(1f)
        isInputBlocked = false
    }
}

@Composable
fun rememberNukeCircularRevealState(
    initiallyRevealed: Boolean = false
): NukeCircularRevealState {
    val coroutineScope = rememberCoroutineScope()
    return remember(coroutineScope) {
        NukeCircularRevealState(
            coroutineScope = coroutineScope,
            initiallyRevealed = initiallyRevealed
        )
    }
}

@Composable
fun NukeCircularRevealHost(
    state: NukeCircularRevealState,
    modifier: Modifier = Modifier,
    backgroundContent: @Composable BoxScope.() -> Unit,
    revealedBackgroundContent: @Composable BoxScope.() -> Unit = {},
    revealedContent: @Composable BoxScope.() -> Unit
) {
    var hostOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                hostOriginInRoot = coordinates.positionInRoot()
            }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (state.isContentVisible) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    }
                ),
            content = backgroundContent
        )

        if (state.isContentVisible) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blockPointerInput()
            )
            val localOrigin = if (state.revealOriginInRoot == Offset.Zero) {
                Offset.Zero
            } else {
                state.revealOriginInRoot - hostOriginInRoot
            }
            NukeCircularRevealLayer(
                progress = { state.progress },
                touchOffset = localOrigin,
                modifier = Modifier.matchParentSize(),
                content = {
                    Box(
                        modifier = Modifier.matchParentSize(),
                        content = revealedBackgroundContent,
                    )
                    Box(
                        modifier = Modifier.matchParentSize(),
                        content = revealedContent,
                    )
                },
            )
        }

        if (state.isInputBlocked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blockPointerInput()
            )
        }
    }
}

private fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
                .changes
                .forEach { change -> change.consume() }
        }
    }
}

@Composable
fun NukeCircularRevealContainer(
    triggered: Boolean,
    touchOffset: Offset,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(triggered) {
        progress.animateTo(
            targetValue = if (triggered) 1f else 0f,
            animationSpec = circularRevealSpring()
        )
    }

    NukeCircularRevealLayer(
        progress = { progress.value },
        touchOffset = touchOffset,
        modifier = modifier,
        content = { content() }
    )
}

@Composable
private fun NukeCircularRevealLayer(
    progress: () -> Float,
    touchOffset: Offset,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var componentSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { componentSize = it }
            .graphicsLayer {
                val actualCenter = if (touchOffset == Offset.Zero) {
                    Offset(componentSize.width / 2f, componentSize.height / 2f)
                } else {
                    touchOffset
                }
                val revealProgress = progress()
                clip = true
                shape = NukeCircularRevealShape(actualCenter, revealProgress)
            },
        content = content
    )
}
