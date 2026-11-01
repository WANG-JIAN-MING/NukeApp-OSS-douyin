package io.github.nuke.oss.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NukeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NukeTheme.colors
    val accentColor = colors.accent
    val hasValue = value.isNotEmpty()
    val lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isFocused) 13.dp else 11.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "TextFieldCornerRadius"
    )
    val borderThickness by animateDpAsState(
        targetValue = if (isFocused && enabled) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 140),
        label = "TextFieldBorderThickness"
    )
    val isMultiline = !singleLine
    val resolvedMinLines = if (singleLine) 1 else minLines.coerceAtLeast(1)
    val resolvedMaxLines = if (singleLine) 1 else maxLines.coerceAtLeast(resolvedMinLines)

    val fieldScaleX by animateFloatAsState(
        targetValue = if (isFocused && enabled) 0.996f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "TextFieldScaleX"
    )
    val fieldScaleY by animateFloatAsState(
        targetValue = if (isFocused && enabled) 1.012f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "TextFieldScaleY"
    )
    val contentOffset by animateDpAsState(
        targetValue = if (isFocused && enabled) 1.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "TextFieldContentOffset"
    )
    val placeholderAlpha by animateFloatAsState(
        targetValue = when {
            hasValue -> 0f
            isFocused -> 0.52f
            else -> 0.68f
        },
        animationSpec = tween(durationMillis = 160),
        label = "TextFieldPlaceholderAlpha"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.45f
            isFocused || hasValue -> 1f
            else -> 0.72f
        },
        animationSpec = tween(durationMillis = 160),
        label = "TextFieldIconAlpha"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused && enabled -> accentColor
            else -> accentColor.copy(alpha = 0f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "TextFieldBorderColor"
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surface.copy(alpha = 0.58f)

            else -> if (colors.isLight) {
                Color.Black.copy(alpha = 0.05f)
            } else {
                Color.Black.copy(alpha = 0.35f)
            }
        },
        animationSpec = tween(durationMillis = 180),
        label = "TextFieldContainerColor"
    )

    val customTextSelectionColors = remember(accentColor) {
        TextSelectionColors(
            handleColor = accentColor,
            backgroundColor = accentColor.copy(alpha = 0.2f)
        )
    }

    CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = {
                if (singleLine) {
                    onValueChange(it.replace('\n', ' ').replace('\r', ' '))
                } else {
                    onValueChange(it)
                }
            },
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = resolvedMinLines,
            maxLines = resolvedMaxLines,
            cursorBrush = SolidColor(accentColor), // the caret follows the accent
            textStyle = TextStyle(
                color = if (enabled) colors.textPrimary else colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = lineHeightStyle
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                val shape = NukeSquircleShape(cornerRadius)

                NukeFluidSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (singleLine) 42.dp else 48.dp)
                        .graphicsLayer {
                            alpha = if (enabled) 1f else 0.62f
                            scaleX = fieldScaleX
                            scaleY = fieldScaleY
                    },
                    shape = shape,
                    color = containerColor,
                    borderColor = borderColor,
                    borderThickness = borderThickness,
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = if (singleLine) 9.dp else 11.dp
                    ),
                    contentAlignment = if (isMultiline) {
                        Alignment.TopStart
                    } else {
                        Alignment.CenterStart
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(x = contentOffset),
                        verticalAlignment = if (isMultiline) {
                            Alignment.Top
                        } else {
                            Alignment.CenterVertically
                        }
                    ) {
                        // leading icon
                        if (leadingIcon != null) {
                            Box(
                                modifier = Modifier.graphicsLayer { alpha = iconAlpha },
                                contentAlignment = Alignment.Center
                            ) {
                                leadingIcon()
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        // input area and placeholder
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 18.dp),
                            contentAlignment = if (isMultiline) {
                                Alignment.TopStart
                            } else {
                                Alignment.CenterStart
                            }
                        ) {
                            BasicText(
                                modifier = Modifier
                                    .graphicsLayer { alpha = placeholderAlpha },
                                text = placeholder,
                                style = TextStyle(
                                    color = if (isFocused) {
                                        accentColor
                                    } else {
                                        colors.textSecondary
                                    },
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Normal,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = lineHeightStyle
                                )
                            )
                            innerTextField()
                        }

                        // trailing icon (clear button, password toggle, ...)
                        if (trailingIcon != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier.graphicsLayer { alpha = iconAlpha },
                                contentAlignment = Alignment.Center
                            ) {
                                trailingIcon()
                            }
                        }
                    }
                }
            }
        )
    }
}
