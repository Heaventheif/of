package org.openfilemanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import org.openfilemanager.app.ui.theme.Dim

/**
 * Material 3 tonal icon container: the icon sits on a rounded surface-container fill
 * (no outline), the way M3 list/leading icons and cards are drawn.
 */
@Composable
fun BorderedIcon(
    icon: ImageVector, modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    boxSize: Dp = Dim.IconBox, iconSize: Dp = Dim.BarIcon
) {
    Box(
        modifier.size(boxSize).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
