package io.github.nuke.oss.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The page the home list and every destination page sit on.
@Composable
fun NukeScreenSurface(modifier: Modifier = Modifier, containerColor: Color = Color.Transparent, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(containerColor)) { content() }
}

// A tinted rounded square holding a glyph; the leading mark of a home list row.
@Composable
fun NukeIconBadge(glyph: Glyph, modifier: Modifier = Modifier, color: Color = NukeTheme.colors.accent) {
    Box(
        modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) { GlyphIcon(glyph, null, iconSize = 18.dp) }
    }
}

@Composable
fun NukeVersionText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(color = NukeTheme.colors.textSecondary.copy(alpha = 0.74f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
fun NukeEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    NukeSettingGroup(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
            BasicText(title, style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 15.sp,
                lineHeight = 20.sp, fontWeight = FontWeight.SemiBold))
            BasicText(message, Modifier.padding(top = 5.dp), style = TextStyle(color = NukeTheme.colors.textSecondary,
                fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal))
        }
    }
}

// Name over identifier on a card, with the accent-tinted avatar. The design system's premium treatment is not carried.
// The avatar slot draws over a person glyph, so an image that is still loading or never arrives leaves the glyph. With
// onAvatarClick the avatar is a button; its description tells a screen reader what it opens.
@Composable
fun NukeUserProfileCard(
    name: String,
    identifier: String,
    modifier: Modifier = Modifier,
    onIdentifierClick: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
    avatarDescription: String? = null,
    avatar: @Composable () -> Unit = {},
) {
    val shape = NukeSquircleShape(16.dp)
    NukeCardSurface(
        modifier = modifier.fillMaxWidth().shadow(1.5.dp, shape,
            ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.08f)),
        shape = shape,
    ) {
        Row(Modifier.fillMaxWidth().height(78.dp).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val avatarModifier = Modifier.size(46.dp).clip(CircleShape).background(NukeTheme.colors.accent.copy(alpha = 0.14f))
                .let { base ->
                    if (onAvatarClick == null) base else base.nukeClick(role = Role.Button, onClick = onAvatarClick)
                        .semantics { avatarDescription?.let { contentDescription = it } }
                }
            Box(avatarModifier, contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides NukeTheme.colors.accent) {
                    GlyphIcon(Glyph.Person, null, iconSize = 24.dp)
                }
                avatar()
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(
                    color = NukeTheme.colors.textPrimary, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold))
                val identifierModifier = if (onIdentifierClick != null) Modifier.nukeClick(onClick = onIdentifierClick) else Modifier
                BasicText(identifier, identifierModifier, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(
                    color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 15.sp))
            }
        }
    }
}

// The top of the home list: a search field, and under it the profile card while no query is typed.
@Composable
fun NukeHomeHero(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    profile: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NukeTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            leadingIcon = {
                CompositionLocalProvider(LocalContentColor provides NukeTheme.colors.textSecondary) {
                    GlyphIcon(Glyph.Search, null, iconSize = 18.dp)
                }
            },
        )
        if (profile != null) profile()
    }
}

// The headline state of a screen: a card tinted with the state colour, a solid mark carrying its glyph, a title and a
// short explanation. A click opens the details behind it.
@Composable
fun NukeStatusCard(
    title: String,
    subtitle: String?,
    glyph: Glyph,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    NukeCardSurface(modifier.fillMaxWidth(), color = color.copy(alpha = 0.14f)) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.nukeClick(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides Color.White) { GlyphIcon(glyph, null, iconSize = 24.dp) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(title, style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 20.sp,
                    lineHeight = 26.sp, fontWeight = FontWeight.Bold))
                if (subtitle != null) BasicText(subtitle, Modifier.padding(top = 4.dp), style = TextStyle(
                    color = NukeTheme.colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal))
            }
            if (onClick != null) NukeNavChevron()
        }
    }
}
