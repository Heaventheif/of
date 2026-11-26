package org.openfilemanager.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import org.openfilemanager.app.OpProgressInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.components.BorderedIcon
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.ui.theme.Dim

/** جزء مستخرج من BrowserScreen.kt (N5): عناصر الواجهة الصغيرة والحواريات. لا تغيير في السلوك. */
internal data class Crumb(val label: String, val onClick: () -> Unit)

internal fun dirCrumbs(dir: File, root: File, rootLabel: String, open: (File) -> Unit): List<Crumb> {
    val chain = ArrayList<File>()
    var cur: File? = dir
    while (cur != null && cur != root) { chain.add(0, cur); cur = cur.parentFile }
    return listOf(Crumb(rootLabel) { open(root) }) + chain.map { f -> Crumb(f.name) { open(f) } }
}

internal fun archiveCrumbs(
    archive: File, inner: String, openArchive: (File) -> Unit, openInner: (String) -> Unit,
    openDir: (File) -> Unit, root: File
): List<Crumb> {
    val parts = inner.split('/').filter { it.isNotEmpty() }
    val base = Crumb(archive.name) { openArchive(archive) }
    return listOf(base) + parts.mapIndexed { i, p -> Crumb(p) { openInner(parts.take(i + 1).joinToString("/") + "/") } }
}

@Composable
internal fun Breadcrumbs(crumbs: List<Crumb>) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs.size) { state.scrollToItem((crumbs.size - 1).coerceAtLeast(0)) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(crumbs) { i, c ->
            val last = i == crumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                Text(
                    c.label, style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.heightIn(min = 36.dp).clickable(enabled = !last, onClick = c.onClick).padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }
        }
    }
}

internal data class ActionBarItem(val icon: ImageVector, val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
internal fun ActionBar(items: List<ActionBarItem>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEach { a ->
                val tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Column(
                    Modifier.weight(1f).heightIn(min = 52.dp).clickable(onClick = a.onClick).padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    BorderedIcon(a.icon, tint = tint)
                    Text(a.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun PasteBar(count: Int, move: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = Dim.Gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(if (move) R.string.ui_moving_n else R.string.ui_copying_n, count),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
            )
            TextButton(onCancel) { Text(stringResource(R.string.ui_cancel)) }
            androidx.compose.material3.Button(onPaste) { Text(stringResource(R.string.ui_paste_here)) }
        }
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(
                        stringResource(R.string.ui_search_here), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    inner()
                }
            }
        }
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onConfirm(name) }) { Text(stringResource(R.string.ui_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

@Composable
internal fun OpsProgressStrip(p: OpProgressInfo, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Dim.Gutter, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (p.itemCount > 0) stringResource(
                    R.string.file_ops_progress_line, p.label, minOf(p.itemIndex + 1, p.itemCount), p.itemCount, BidiText.wrap(p.currentFile)
                ) else "${p.label} — ${BidiText.wrap(p.currentFile)}",
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onCancel) { Text(stringResource(R.string.file_ops_cancel)) }
        }
        if (p.bytesTotal > 0) LinearProgressIndicator(
            progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
        ) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
    }
}
