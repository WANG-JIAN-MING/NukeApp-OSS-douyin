package io.github.nuke.oss.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.normalizeHex
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.GlyphIcon
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeTextField
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.nukeClick

private data class Hsv(val hue: Float, val saturation: Float, val value: Float)

private fun Color.toHsv(): Hsv {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return Hsv(hsv[0].coerceIn(0f, 359.999f), hsv[1].coerceIn(0f, 1f), hsv[2].coerceIn(0f, 1f))
}

private fun Hsv.toColor(): Color = Color(android.graphics.Color.HSVToColor(
    floatArrayOf(hue.coerceIn(0f, 359.999f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)),
))

// Presets, recently used colours, a hex field and a saturation/value square with a hue slider. Save stores the
// draft only when it is a valid colour other than the current accent.
@Composable
internal fun AccentColorDialog(
    visible: Boolean,
    currentHex: String,
    recent: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by rememberSaveable(visible) { mutableStateOf(currentHex) }
    val normalized = normalizeHex(draft)
    var hsv by remember { mutableStateOf((normalized.toAccentColor() ?: DefaultAccentHex.toAccentColor()!!).toHsv()) }
    // A preset or typed hex moves the palette handles; dragging them writes the hex back.
    LaunchedEffect(normalized) {
        normalized.toAccentColor()?.toHsv()?.takeIf { it.toColor().toHex() != hsv.toColor().toHex() }?.let { hsv = it }
    }
    val canSave = normalized != null && normalized != currentHex
    NukeDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.home_accent_dialog_title),
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f))
            NukeDialogAccentButton(stringResource(R.string.home_save), { normalized?.let(onSave) }, Modifier.weight(1f), enabled = canSave)
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.home_accent_presets))
            SwatchGrid(PresetAccentHexes.mapNotNull { it.toAccentColor() }, normalized) { draft = it }
            val history = recent.filter { it !in PresetAccentHexes }.mapNotNull { it.toAccentColor() }
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.home_accent_recent))
                SwatchGrid(history, normalized) { draft = it }
            }
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.home_accent_custom))
            NukeTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = DefaultAccentHex,
                trailingIcon = { Swatch(normalized.toAccentColor(), size = 22.dp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            )
            BasicText(
                normalized ?: stringResource(R.string.home_accent_invalid),
                Modifier.padding(top = 8.dp),
                style = TextStyle(
                    color = NukeTheme.colors.textSecondary.copy(alpha = if (normalized == null) 0.72f else 1f),
                    fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.home_accent_palette))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SaturationValueSquare(hsv) { hsv = it; draft = it.toColor().toHex() }
                HueSlider(hsv) { hsv = it; draft = it.toColor().toHex() }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text, style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 13.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwatchGrid(colors: List<Color>, selectedHex: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { color ->
                    val hex = remember(color) { color.toHex() }
                    Swatch(color, selected = hex == selectedHex, size = 34.dp, onClick = { onSelect(hex) })
                }
            }
        }
    }
}

// A rounded colour square. A null colour is an invalid entry and is marked with "!".
@Composable
internal fun Swatch(color: Color?, modifier: Modifier = Modifier, selected: Boolean = false, size: Dp = 42.dp, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.size(size).clip(shape).background(color ?: NukeTheme.colors.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                when {
                    selected -> NukeTheme.colors.textPrimary
                    color == null -> NukeTheme.colors.border
                    else -> NukeTheme.colors.textSecondary.copy(alpha = 0.18f)
                },
                shape,
            )
            .then(if (onClick != null) Modifier.nukeClick(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            selected && color != null -> CompositionLocalProvider(
                LocalContentColor provides if (color.luminance() > 0.58f) Color.Black.copy(alpha = 0.72f) else Color.White,
            ) { GlyphIcon(Glyph.Check, null, iconSize = size * 0.52f) }
            color == null -> BasicText("!", style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 16.sp,
                fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun SaturationValueSquare(hsv: Hsv, onChange: (Hsv) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(hsv)
    val onChangeNow by rememberUpdatedState(onChange)
    val hueColor = remember(hsv.hue) { hsv.copy(saturation = 1f, value = 1f).toColor() }
    val shape = RoundedCornerShape(12.dp)
    fun update(position: Offset) {
        if (size.width <= 0 || size.height <= 0) return
        onChangeNow(current.copy(
            saturation = (position.x / size.width).coerceIn(0f, 1f),
            value = (1f - position.y / size.height).coerceIn(0f, 1f),
        ))
    }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).border(1.dp, NukeTheme.colors.border, shape)
            .onSizeChanged { size = it }
            .pointerInput(size) { dragGesture(::update) },
    ) {
        val corner = CornerRadius(12.dp.toPx(), 12.dp.toPx())
        drawRoundRect(Brush.horizontalGradient(listOf(Color.White, hueColor)), cornerRadius = corner)
        drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), cornerRadius = corner)
        val handle = Offset(hsv.saturation.coerceIn(0f, 1f) * this.size.width, (1f - hsv.value.coerceIn(0f, 1f)) * this.size.height)
        drawCircle(Color.Black.copy(alpha = 0.58f), 9.dp.toPx(), handle, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), handle, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun HueSlider(hsv: Hsv, onChange: (Hsv) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(hsv)
    val onChangeNow by rememberUpdatedState(onChange)
    val gradient = remember { listOf(0f, 60f, 120f, 180f, 240f, 300f, 359.999f).map { Hsv(it, 1f, 1f).toColor() } }
    val selected = remember(hsv.hue) { hsv.copy(saturation = 1f, value = 1f).toColor() }
    val shape = RoundedCornerShape(999.dp)
    fun update(position: Offset) {
        if (size.width <= 0) return
        onChangeNow(current.copy(hue = ((position.x / size.width).coerceIn(0f, 1f) * 359.999f).coerceIn(0f, 359.999f)))
    }
    Canvas(
        Modifier.fillMaxWidth().height(28.dp).clip(shape).border(1.dp, NukeTheme.colors.border, shape)
            .onSizeChanged { size = it }
            .pointerInput(size) { dragGesture(::update) },
    ) {
        drawRoundRect(Brush.horizontalGradient(gradient), cornerRadius = CornerRadius(this.size.height / 2f, this.size.height / 2f))
        val handle = Offset(hsv.hue.coerceIn(0f, 359.999f) / 359.999f * this.size.width, this.size.height / 2f)
        drawCircle(Color.White, 10.dp.toPx(), handle, style = Stroke(3.dp.toPx()))
        drawCircle(selected, 6.dp.toPx(), handle)
    }
}

// Follows one finger from touch down to release, reporting every position.
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.dragGesture(update: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        update(down.position)
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            update(change.position)
            change.consume()
            if (!change.pressed) break
        }
    }
}
