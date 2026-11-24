package org.openfilemanager.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.theme.Dim

/** Opens the side menu; provided by the app shell so every top-level screen can show a menu button. */
val LocalOpenDrawer = compositionLocalOf<() -> Unit> { {} }

/** Side menu: main destinations, pinned folders, settings and about. */
@Composable
fun AppDrawer(
    route: Route,
    pinned: List<String>,
    onHome: () -> Unit,
    onFiles: () -> Unit,
    onLibrary: () -> Unit,
    onRecent: () -> Unit,
    onOpenPinned: (File) -> Unit,
    onUnpin: (String) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dim.Gutter + 12.dp, vertical = 12.dp)
            )
            DrawerItem(Icons.Outlined.Home, stringResource(R.string.ui_home), route is Route.Home, onHome)
            DrawerItem(Icons.Outlined.Folder, stringResource(R.string.ui_files), route is Route.Files, onFiles)
            DrawerItem(Icons.Outlined.PhotoLibrary, stringResource(R.string.ui_library), route is Route.Media, onLibrary)
            DrawerItem(Icons.Outlined.History, stringResource(R.string.drawer_recent), route is Route.Recent, onRecent)
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = Dim.Gutter), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.drawer_pinned), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Dim.Gutter + 12.dp, vertical = 6.dp)
            )
            val existing = pinned.map { File(it) }.filter { it.isDirectory }
            if (existing.isEmpty()) {
                Text(
                    stringResource(R.string.drawer_no_pinned), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dim.Gutter + 12.dp, vertical = 6.dp)
                )
            }
            existing.forEach { dir ->
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    NavigationDrawerItem(
                        label = { Text(BidiText.wrap(dir.name.ifEmpty { dir.path }), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(Icons.Outlined.Folder, null) },
                        selected = false, onClick = { onOpenPinned(dir) }, modifier = Modifier.weight(1f)
                    )
                    IconButton({ onUnpin(dir.path) }) { Icon(Icons.Outlined.PushPin, stringResource(R.string.drawer_unpin), tint = MaterialTheme.colorScheme.primary) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = Dim.Gutter), color = MaterialTheme.colorScheme.outlineVariant)
            DrawerItem(Icons.Outlined.Settings, stringResource(R.string.ui_settings), route is Route.Settings, onSettings)
            DrawerItem(Icons.Outlined.Info, stringResource(R.string.ui_settings_about), false, onAbout)
        }
    }
}

@Composable
private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) }, icon = { Icon(icon, null) }, selected = selected, onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}
