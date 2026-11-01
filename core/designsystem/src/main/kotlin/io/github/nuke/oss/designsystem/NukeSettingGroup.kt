package io.github.nuke.oss.designsystem

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun NukeSettingGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Let the page transition settle before groups begin entering.
        appeared = true
    }

    val shape = NukeSquircleShape(16.dp)
    val groupAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = 180,
            easing = LinearOutSlowInEasing,
        ),
        label = "SettingGroupAlphaAnimation",
    )
    val groupScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.96f,
        animationSpec = tween(
            durationMillis = 220,
            easing = FastOutSlowInEasing,
        ),
        label = "SettingGroupScaleAnimation",
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = 180,
            easing = LinearOutSlowInEasing,
        ),
        label = "SettingGroupTitleAlphaAnimation"
    )
    val titleOffset by animateDpAsState(
        targetValue = if (appeared) 0.dp else 4.dp,
        animationSpec = tween(
            durationMillis = 180,
            easing = FastOutSlowInEasing,
        ),
        label = "SettingGroupTitleOffsetAnimation"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .offset(y = titleOffset)
                    .graphicsLayer { alpha = titleAlpha }
                    .padding(start = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    color = NukeTheme.colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    modifier = Modifier
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = groupAlpha
                    scaleX = groupScale
                    scaleY = groupScale
                    transformOrigin = TransformOrigin.Center
                },
        ) {
            NukeCardSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Dividers are drawn explicitly by the content; the group only provides the surface.
                    content()
                }
            }
        }
    }
}
