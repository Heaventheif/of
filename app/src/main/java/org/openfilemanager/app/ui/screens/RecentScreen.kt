package org.openfilemanager.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.ui.LocalOpenDrawer
import org.openfilemanager.app.ui.LocalSnack
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.EmptyState
import org.openfilemanager.app.ui.components.FileRow
import org.openfilemanager.app.ui.components.OfmSnackbarHost
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.components.SnackKind
import org.openfilemanager.app.ui.components.show
import org.openfilemanager.app.ui.toFileItem

/** آخر الملفات المفتوحة (من القائمة الجانبية) مع إزالة عنصر أو مسح السجل كله. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(onOpenFile: (File) -> Unit, bottomBar: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val registry = remember { ctx.appContainer.lastOpened }
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    fun load(): List<File> = registry.recent().map { it.path.toFile() }.filter { it.exists() }
    var files by remember { mutableStateOf(load()) }
    var confirmClear by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<File?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { OfmSnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_recent)) },
                navigationIcon = { IconButton(LocalOpenDrawer.current) { Icon(Icons.Outlined.Menu, stringResource(R.string.drawer_menu)) } },
                actions = {
                    if (files.isNotEmpty()) IconButton({ confirmClear = true }) {
                        Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.recent_clear))
                    }
                }
            )
        },
        bottomBar = bottomBar
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (files.isEmpty()) {
                EmptyState(Icons.Outlined.History, stringResource(R.string.recent_empty), Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        FileRow(f.toFileItem(), false, false, { onOpenFile(f) }, { menuFor = f }, { menuFor = f })
                    }
                }
            }
        }
    }

    menuFor?.let { f ->
        ActionSheet(
            BidiText.wrap(f.name),
            listOf(SheetAction(Icons.Outlined.Close, stringResource(R.string.recent_remove)) {
                registry.remove(f.toPath())
                files = load()
                menuFor = null
            })
        ) { menuFor = null }
    }
    if (confirmClear) {
        val doneText = stringResource(R.string.recent_cleared)
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.recent_clear)) },
            text = { Text(stringResource(R.string.recent_clear_confirm)) },
            confirmButton = {
                TextButton({
                    registry.clear()
                    files = emptyList()
                    confirmClear = false
                    scope.launch { snack.show(doneText, SnackKind.SUCCESS) }
                }) { Text(stringResource(R.string.recent_clear)) }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
}
