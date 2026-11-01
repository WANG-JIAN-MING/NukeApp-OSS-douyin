package io.github.nuke.oss.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph {
    Search, Close, ChevronLeft, ChevronRight,
    Bug, Palette, Info, Tap, Play, Grid, Monitor, Package, Sliders, Check,
    Feed, Gauge, Block, Danmaku, Comment, Chat, Gift, Share, Person, Shield, Clock, Phone, Restart, Trash, More, Link,
    Code, Document, Terminal, Warning, Puzzle, Store, Upload, Book, CheckCircle, Circle,
}

// A few stroked 24-unit glyphs drawn directly, so the screen needs no icon library.
@Composable
fun GlyphIcon(glyph: Glyph, description: String?, modifier: Modifier = Modifier, iconSize: Dp = 24.dp) {
    val color = LocalContentColor.current
    Canvas(modifier.size(iconSize).then(if (description != null) Modifier.semantics { this.contentDescription = description } else Modifier)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), stroke.width, StrokeCap.Round)
        fun dot(x: Float, y: Float, radius: Float) = drawCircle(color, radius = radius * unit, center = point(x, y))
        fun outline(vararg points: Pair<Float, Float>) = drawPath(Path().apply {
            points.forEachIndexed { index, (x, y) -> if (index == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit) }
            close()
        }, color, style = stroke)
        fun arc(x: Float, y: Float, diameter: Float, start: Float, sweep: Float) =
            drawArc(color, start, sweep, false, point(x, y), Size(diameter * unit, diameter * unit), style = stroke)
        fun box(x: Float, y: Float, width: Float, height: Float, radius: Float) =
            drawRoundRect(color, point(x, y), Size(width * unit, height * unit), CornerRadius(radius * unit), style = stroke)
        when (glyph) {
            Glyph.Search -> {
                drawCircle(color, radius = 6f * unit, center = point(10f, 10f), style = stroke)
                drawLine(color, point(14.5f, 14.5f), point(20f, 20f), stroke.width, StrokeCap.Round)
            }
            Glyph.Close -> {
                drawLine(color, point(6f, 6f), point(18f, 18f), stroke.width, StrokeCap.Round)
                drawLine(color, point(18f, 6f), point(6f, 18f), stroke.width, StrokeCap.Round)
            }
            Glyph.ChevronLeft -> {
                drawLine(color, point(14.5f, 5.5f), point(8f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, point(8f, 12f), point(14.5f, 18.5f), stroke.width, StrokeCap.Round)
            }
            Glyph.ChevronRight -> {
                drawLine(color, point(9.5f, 5.5f), point(16f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, point(16f, 12f), point(9.5f, 18.5f), stroke.width, StrokeCap.Round)
            }
            Glyph.Bug -> {
                box(8f, 8f, 8f, 11f, 4f)
                line(12f, 8f, 12f, 19f)
                line(9f, 4.5f, 10.5f, 7f); line(15f, 4.5f, 13.5f, 7f)
                line(4f, 10f, 8f, 11.5f); line(4f, 16f, 8f, 15f)
                line(20f, 10f, 16f, 11.5f); line(20f, 16f, 16f, 15f)
            }
            Glyph.Palette -> {
                drawCircle(color, radius = 9f * unit, center = point(12f, 12f), style = stroke)
                dot(8f, 10.5f, 1.3f); dot(12f, 7.8f, 1.3f); dot(16f, 10.5f, 1.3f); dot(8.5f, 15f, 1.3f)
            }
            Glyph.Info -> {
                drawCircle(color, radius = 9f * unit, center = point(12f, 12f), style = stroke)
                line(12f, 11f, 12f, 16.5f)
                dot(12f, 7.8f, 1.2f)
            }
            Glyph.Tap -> {
                drawCircle(color, radius = 8f * unit, center = point(12f, 12f), style = stroke)
                dot(12f, 12f, 2.6f)
            }
            Glyph.Play -> outline(8f to 5.5f, 18.5f to 12f, 8f to 18.5f)
            Glyph.Grid -> {
                box(4f, 4f, 6.5f, 6.5f, 1.8f); box(13.5f, 4f, 6.5f, 6.5f, 1.8f)
                box(4f, 13.5f, 6.5f, 6.5f, 1.8f); box(13.5f, 13.5f, 6.5f, 6.5f, 1.8f)
            }
            Glyph.Monitor -> {
                box(3f, 4.5f, 18f, 12f, 2.5f)
                line(9f, 20f, 15f, 20f); line(12f, 16.5f, 12f, 20f)
            }
            Glyph.Package -> {
                outline(12f to 3f, 20f to 7.5f, 20f to 16.5f, 12f to 21f, 4f to 16.5f, 4f to 7.5f)
                line(4f, 7.5f, 12f, 12f); line(20f, 7.5f, 12f, 12f); line(12f, 12f, 12f, 21f)
            }
            Glyph.Check -> {
                line(5f, 12.5f, 10f, 17.5f); line(10f, 17.5f, 19f, 7f)
            }
            Glyph.More -> {
                dot(12f, 5.5f, 1.8f); dot(12f, 12f, 1.8f); dot(12f, 18.5f, 1.8f)
            }
            Glyph.Link -> {
                box(3.5f, 9f, 9.5f, 6f, 3f); box(11f, 9f, 9.5f, 6f, 3f)
            }
            Glyph.Sliders -> {
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                dot(9f, 7f, 2.4f); dot(15f, 12f, 2.4f); dot(8f, 17f, 2.4f)
            }
            Glyph.Feed -> {
                box(5f, 3.5f, 14f, 10f, 2.2f)
                line(6.5f, 16.5f, 17.5f, 16.5f); line(8f, 20f, 16f, 20f)
            }
            Glyph.Gauge -> {
                arc(4f, 5f, 16f, 140f, 260f)
                line(12f, 13f, 15.5f, 9f)
                dot(12f, 13f, 1.6f)
            }
            Glyph.Block -> {
                drawCircle(color, radius = 8.5f * unit, center = point(12f, 12f), style = stroke)
                line(6f, 18f, 18f, 6f)
            }
            Glyph.Danmaku -> {
                box(3f, 5f, 18f, 14f, 2.5f)
                line(6.5f, 9.5f, 13.5f, 9.5f); line(10f, 12.5f, 17.5f, 12.5f); line(6.5f, 15.5f, 11.5f, 15.5f)
            }
            Glyph.Comment -> {
                box(3.5f, 4.5f, 17f, 12f, 3f)
                line(8f, 16.5f, 7.5f, 20.5f); line(7.5f, 20.5f, 12f, 16.5f)
                dot(8.5f, 10.5f, 1.2f); dot(12f, 10.5f, 1.2f); dot(15.5f, 10.5f, 1.2f)
            }
            Glyph.Chat -> {
                box(3.5f, 4.5f, 17f, 12f, 3f)
                line(8f, 16.5f, 7.5f, 20.5f); line(7.5f, 20.5f, 12f, 16.5f)
                line(7.5f, 9f, 16.5f, 9f); line(7.5f, 12.5f, 13.5f, 12.5f)
            }
            Glyph.Gift -> {
                box(4.5f, 10f, 15f, 10f, 1.5f); box(3f, 6.5f, 18f, 3.5f, 1.2f)
                line(12f, 6.5f, 12f, 20f)
                line(12f, 6.5f, 9f, 3.5f); line(12f, 6.5f, 15f, 3.5f)
            }
            Glyph.Share -> {
                dot(17f, 6f, 2.2f); dot(7f, 12f, 2.2f); dot(17f, 18f, 2.2f)
                line(9f, 11f, 15f, 7f); line(9f, 13f, 15f, 17f)
            }
            Glyph.Person -> {
                drawCircle(color, radius = 3.5f * unit, center = point(12f, 8.5f), style = stroke)
                arc(5f, 14.5f, 14f, 180f, 180f)
            }
            Glyph.Shield -> {
                outline(12f to 3f, 19.5f to 6f, 19.5f to 12f, 12f to 21f, 4.5f to 12f, 4.5f to 6f)
                line(9f, 12f, 11.2f, 14.2f); line(11.2f, 14.2f, 15f, 10f)
            }
            Glyph.Clock -> {
                drawCircle(color, radius = 8.5f * unit, center = point(12f, 12f), style = stroke)
                line(12f, 7.5f, 12f, 12f); line(12f, 12f, 15.5f, 14f)
            }
            Glyph.Phone -> {
                box(7f, 3f, 10f, 18f, 2.5f)
                dot(12f, 17.5f, 1.1f)
            }
            Glyph.Restart -> {
                // Clockwise from the upper right, with the head at the end pointing the way it turns.
                arc(4f, 4f, 16f, -45f, 290f)
                line(8.6f, 4.8f, 6.5f, 8.5f); line(8.6f, 4.8f, 4.4f, 4f)
            }
            Glyph.Trash -> {
                line(4f, 6.5f, 20f, 6.5f)
                line(9.5f, 6.5f, 10f, 3.5f); line(10f, 3.5f, 14f, 3.5f); line(14f, 3.5f, 14.5f, 6.5f)
                outline(6f to 6.5f, 18f to 6.5f, 17f to 20.5f, 7f to 20.5f)
                line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
            }
            Glyph.Code -> {
                line(8.5f, 7f, 3.5f, 12f); line(3.5f, 12f, 8.5f, 17f)
                line(15.5f, 7f, 20.5f, 12f); line(20.5f, 12f, 15.5f, 17f)
                line(13.5f, 5f, 10.5f, 19f)
            }
            Glyph.Document -> {
                outline(6f to 3f, 14f to 3f, 19f to 8f, 19f to 21f, 6f to 21f)
                line(14f, 3f, 14f, 8f); line(14f, 8f, 19f, 8f)
                line(9f, 12.5f, 16f, 12.5f); line(9f, 16.5f, 16f, 16.5f)
            }
            Glyph.Terminal -> {
                box(3f, 4.5f, 18f, 15f, 2.5f)
                line(7f, 9.5f, 10f, 12f); line(10f, 12f, 7f, 14.5f)
                line(12f, 15f, 16.5f, 15f)
            }
            Glyph.Warning -> {
                drawCircle(color, radius = 9f * unit, center = point(12f, 12f), style = stroke)
                line(12f, 7.5f, 12f, 13f)
                dot(12f, 16.4f, 1.2f)
            }
            Glyph.Puzzle -> {
                box(4f, 7f, 13f, 13f, 2f)
                arc(8.5f, 3.5f, 4f, 180f, 180f)
                arc(17f, 11.5f, 4f, 270f, 180f)
            }
            Glyph.Store -> {
                line(3.5f, 9f, 5.5f, 4f); line(5.5f, 4f, 18.5f, 4f); line(18.5f, 4f, 20.5f, 9f); line(3.5f, 9f, 20.5f, 9f)
                outline(5f to 9f, 19f to 9f, 19f to 20f, 5f to 20f)
                box(9.5f, 13.5f, 5f, 6.5f, 1f)
            }
            Glyph.Upload -> {
                line(12f, 15f, 12f, 4f)
                line(7.5f, 8.5f, 12f, 4f); line(12f, 4f, 16.5f, 8.5f)
                line(4f, 15f, 4f, 20f); line(4f, 20f, 20f, 20f); line(20f, 20f, 20f, 15f)
            }
            Glyph.Book -> {
                outline(3f to 5f, 9f to 5f, 12f to 7.5f, 12f to 20f, 9f to 18f, 3f to 18f)
                outline(21f to 5f, 15f to 5f, 12f to 7.5f, 12f to 20f, 15f to 18f, 21f to 18f)
            }
            Glyph.CheckCircle -> {
                drawCircle(color, radius = 9f * unit, center = point(12f, 12f), style = stroke)
                line(8f, 12.5f, 11f, 15.5f); line(11f, 15.5f, 16.5f, 9f)
            }
            Glyph.Circle -> drawCircle(color, radius = 9f * unit, center = point(12f, 12f), style = stroke)
        }
    }
}
