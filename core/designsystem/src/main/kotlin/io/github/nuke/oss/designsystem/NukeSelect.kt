package io.github.nuke.oss.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Composable
fun <T> NukeSelectRow(
    title: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    optionToString: (T) -> String = { it.toString() },
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val expandedState = remember {
        MutableTransitionState(false)
    }
    var opensAbove by remember { mutableStateOf(false) }
    val isExpanded = expandedState.targetState || expandedState.currentState

    val arrowRotation by animateFloatAsState(
        targetValue = if (expandedState.targetState) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ArrowRotation"
    )
    val arrowScale by animateFloatAsState(
        targetValue = if (expandedState.targetState) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "ArrowScale"
    )

    Box(modifier = modifier) {
        NukeSettingRow(
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            onClick = {
                if (enabled) {
                    expandedState.targetState = true
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = optionToString(selectedOption),
                        style = TextStyle(
                            color = if (enabled) NukeTheme.colors.accent else NukeTheme.colors.textSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    NukeSelectChevron(
                        modifier = Modifier.graphicsLayer {
                            rotationZ = arrowRotation
                            scaleX = arrowScale
                            scaleY = arrowScale
                        },
                        color = if (enabled) NukeTheme.colors.textSecondary else NukeTheme.colors.textSecondary.copy(alpha = 0.5f)
                    )
                }
            }
        )

        if (isExpanded && enabled) {
            val positionProvider = remember(density) {
                NukeSelectPositionProvider(
                    menuOverlapPx = with(density) { 18.dp.roundToPx() },
                    windowMarginPx = with(density) { 12.dp.roundToPx() },
                    onPlacementChanged = { opensAbove = it }
                )
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expandedState.targetState = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = true
                )
            ) {
                AnimatedVisibility(
                    visibleState = expandedState,
                    enter = fadeIn(animationSpec = tween(durationMillis = 90)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                        scaleOut(
                            targetScale = 0.96f,
                            transformOrigin = menuTransformOrigin(opensAbove),
                            animationSpec = tween(durationMillis = 120)
                        )
                ) {
                    NukeDropdownMenuPanel(
                        options = options,
                        selectedOption = selectedOption,
                        onOptionSelected = {
                            onOptionSelected(it)
                            expandedState.targetState = false
                        },
                        optionToString = optionToString,
                        opensAbove = opensAbove
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> NukeDropdownMenuPanel(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    optionToString: (T) -> String,
    opensAbove: Boolean
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }

    val panelScaleX by animateFloatAsState(
        targetValue = if (entered) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SelectPanelScaleX"
    )
    val panelScaleY by animateFloatAsState(
        targetValue = if (entered) 1f else 0.82f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "SelectPanelScaleY"
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "SelectPanelAlpha"
    )
    val panelEdgeColor by animateColorAsState(
        targetValue = if (entered) {
            NukeTheme.colors.textSecondary.copy(alpha = 0.035f)
        } else {
            NukeTheme.colors.accent.copy(alpha = 0.14f)
        },
        animationSpec = tween(durationMillis = 150),
        label = "SelectPanelFluidEdgeColor"
    )
    val panelEdgeThickness by animateDpAsState(
        targetValue = if (entered) 1.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SelectPanelFluidEdgeThickness"
    )

    val panelShape = NukeSquircleShape(14.dp)
    val optionShape = NukeSquircleShape(10.dp)

    // The popup window is clipped to the screen, so a long list (24 hours, 60 minutes) must scroll inside a bounded
    // panel; half the window keeps room to open either above or below the row. The open menu starts with the selected
    // option centered, not at the top of a list whose later rows would otherwise be out of sight.
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxListHeight = with(density) { (windowHeight * 0.5f).toDp() }.coerceAtLeast(160.dp)
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var selectedTop by remember { mutableIntStateOf(-1) }
    LaunchedEffect(selectedTop, viewportHeight) {
        if (selectedTop >= 0 && viewportHeight > 0) scroll.scrollTo((selectedTop - viewportHeight / 2).coerceAtLeast(0))
    }

    NukeFluidSurface(
        modifier = Modifier
            .graphicsLayer {
                alpha = panelAlpha
                scaleX = panelScaleX
                scaleY = panelScaleY
                transformOrigin = menuTransformOrigin(opensAbove)
            }
            .width(IntrinsicSize.Max)
            .widthIn(min = 160.dp, max = 280.dp),
        shape = panelShape,
        edgeColor = panelEdgeColor,
        edgeThickness = panelEdgeThickness,
        contentPadding = PaddingValues(vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxListHeight)
                .onSizeChanged { viewportHeight = it.height }
                .verticalScroll(scroll)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clip(optionShape)
                        .background(
                            if (isSelected) {
                                NukeTheme.colors.accent.copy(alpha = 0.09f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .then(
                            if (isSelected) Modifier.onPlaced { selectedTop = it.positionInParent().y.toInt() } else Modifier
                        )
                        .nukeClick { onOptionSelected(option) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // option text
                    BasicText(
                        text = optionToString(option),
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            color = if (isSelected) NukeTheme.colors.textPrimary else NukeTheme.colors.textPrimary.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )

                    if (isSelected) {
                        Spacer(modifier = Modifier.width(12.dp))
                        NukeSelectCheckmark(
                            color = NukeTheme.colors.accent
                        )
                    }
                }
            }
        }
    }
}

internal class NukeSelectPositionProvider(
    private val menuOverlapPx: Int,
    private val windowMarginPx: Int,
    private val onPlacementChanged: (opensAbove: Boolean) -> Unit
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val maxX = windowSize.width - popupContentSize.width - windowMarginPx
        val x = preferredX.coerceIn(
            minimumValue = windowMarginPx,
            maximumValue = maxX.coerceAtLeast(windowMarginPx)
        )

        val anchorCenterY = (anchorBounds.top + anchorBounds.bottom) / 2
        val belowY = anchorCenterY + menuOverlapPx
        val aboveY = anchorCenterY - menuOverlapPx - popupContentSize.height
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height - windowMarginPx
        val fitsAbove = aboveY >= windowMarginPx
        val y = when {
            fitsBelow -> belowY
            fitsAbove -> aboveY
            anchorBounds.top > windowSize.height - anchorBounds.bottom -> {
                aboveY.coerceAtLeast(windowMarginPx)
            }
            else -> {
                belowY.coerceAtMost(
                    (windowSize.height - popupContentSize.height - windowMarginPx)
                        .coerceAtLeast(windowMarginPx)
                )
            }
        }
        onPlacementChanged(y < anchorBounds.top)

        return IntOffset(x, y)
    }
}

internal fun menuTransformOrigin(opensAbove: Boolean): TransformOrigin {
    return TransformOrigin(
        pivotFractionX = 0.82f,
        pivotFractionY = if (opensAbove) 1f else 0f
    )
}

@Composable
private fun NukeSelectChevron(
    modifier: Modifier = Modifier,
    color: Color
) {
    Canvas(modifier = modifier.size(width = 14.dp, height = 14.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val left = Offset(size.width * 0.25f, size.height * 0.38f)
        val center = Offset(size.width * 0.5f, size.height * 0.62f)
        val right = Offset(size.width * 0.75f, size.height * 0.38f)

        drawLine(
            color = color,
            start = left,
            end = center,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = center,
            end = right,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun NukeSelectCheckmark(
    modifier: Modifier = Modifier,
    color: Color
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        appeared = true
    }

    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SelectCheckmarkScale"
    )

    Canvas(
        modifier = modifier
            .size(18.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        val strokeWidth = 2.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.52f)
            lineTo(size.width * 0.42f, size.height * 0.72f)
            lineTo(size.width * 0.78f, size.height * 0.28f)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
