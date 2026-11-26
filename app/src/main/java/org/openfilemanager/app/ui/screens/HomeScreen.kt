package org.openfilemanager.app.ui.screens

import android.os.Environment
import androidx.compose.material.icons.outlined.Menu
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import org.openfilemanager.app.LibraryTab
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.ui.components.BorderedIcon
import org.openfilemanager.app.ui.theme.Dim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenFolder: (File) -> Unit,
    onOpenMedia: (LibraryTab) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenSettings: () -> Unit,
    bottomBar: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    // بطاقة التخزين عبر طبقة التخزين الموحدة لا Environment مباشرة:
    // الجذر والتسمية يأتيان من الوحدة الفعلية (داخلي/قابل للإزالة/وضع محدود).
    val primary = remember { ctx.appContainer.storage.roots().firstOrNull() }
    val rootFile: File = primary?.path?.toFile() ?: Environment.getExternalStorageDirectory()
    val rootLabel: String = primary?.label ?: stringResource(R.string.ui_internal_storage)
    val stat = remember { StatFs(rootFile.path) }
    val total = stat.totalBytes
    val free = stat.availableBytes
    val usedFraction = if (total > 0) (total - free).toFloat() / total else 0f

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { org.openfilemanager.app.ui.components.OfmSnackbarHost(org.openfilemanager.app.ui.LocalSnack.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(org.openfilemanager.app.ui.LocalOpenDrawer.current) {
                        Icon(Icons.Outlined.Menu, stringResource(R.string.drawer_menu))
                    }
                },
                scrollBehavior = scroll
            )
        },
        bottomBar = bottomBar
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = Dim.Gutter, vertical = 8.dp)
                        .clickable { onOpenFolder(rootFile) },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rootLabel, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.ui_used_free, Formatter.formatShortFileSize(ctx, total - free), Formatter.formatShortFileSize(ctx, free)),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("${(usedFraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { usedFraction }, modifier = Modifier.fillMaxWidth().height(6.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.ui_quick_access)) }
            item {
                val cats = listOf(
                    Cat(Icons.Outlined.Download, R.string.ui_downloads) { onOpenFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) },
                    Cat(Icons.Outlined.Image, R.string.ui_images) { onOpenMedia(LibraryTab.IMAGES) },
                    Cat(Icons.Outlined.VideoLibrary, R.string.ui_videos) { onOpenMedia(LibraryTab.VIDEOS) },
                    Cat(Icons.Outlined.MusicNote, R.string.ui_music) { onOpenMedia(LibraryTab.AUDIO) },
                    Cat(Icons.Outlined.Description, R.string.ui_documents) { onOpenMedia(LibraryTab.DOCUMENTS) },
                    Cat(Icons.Outlined.Android, R.string.ui_apps) { onOpenMedia(LibraryTab.APK) }
                )
                Column(Modifier.padding(horizontal = Dim.Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    cats.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { c -> CategoryCard(c, Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

private data class Cat(val icon: ImageVector, val label: Int, val onClick: () -> Unit)

@Composable
private fun CategoryCard(c: Cat, modifier: Modifier) {
    Card(
        modifier.height(Dim.HomeCard).clickable(onClick = c.onClick), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BorderedIcon(c.icon, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(c.label), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dim.Gutter + 4.dp, end = Dim.Gutter, top = 16.dp, bottom = 6.dp)
    )
}
