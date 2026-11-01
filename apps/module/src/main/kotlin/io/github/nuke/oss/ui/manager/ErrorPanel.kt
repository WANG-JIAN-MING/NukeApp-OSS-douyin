package io.github.nuke.oss.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nuke.oss.R
import io.github.nuke.oss.designsystem.NukeCardSurface
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.StatusDot

@Composable
internal fun ErrorPanel(message: String) {
    val colors = NukeTheme.colors
    NukeCardSurface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.danger.copy(alpha = 0.12f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(colors.danger, filled = true)
                Text(stringResource(R.string.manager_failure_title),
                    Modifier.padding(start = 10.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = colors.danger)
            }
            SelectionContainer {
                Text(message, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            }
        }
    }
}
