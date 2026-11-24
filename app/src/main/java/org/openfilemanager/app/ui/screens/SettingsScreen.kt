package org.openfilemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import org.openfilemanager.app.LanguageManager
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
// (تصحيح F13) اسم الحزمة الصحيح nestedscroll بحرف صغير: الحزمة nestedScroll لا وجود لها
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.ui.BrowserViewModel
import org.openfilemanager.app.ui.components.SortSheet
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.app.ui.theme.ThemeManager
import org.openfilemanager.core.SortFieldCore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(browser: BrowserViewModel, onBack: () -> Unit) {
    val settings = LocalContext.current.appContainer.settings
    var hidden by remember { mutableStateOf(settings.showHidden) }
    var foldersFirst by remember { mutableStateOf(settings.foldersFirst) }
    var grid by remember { mutableStateOf(settings.gridView) }
    var sort by remember { mutableStateOf(settings.defaultSort) }
    var showSort by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showLanguage by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    val language = remember { LanguageManager.current(context) }
    BackHandler(onBack = onBack)

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_settings)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) } },
                scrollBehavior = scroll
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.ui_settings_browsing))
            SwitchRow(stringResource(R.string.ui_show_hidden), hidden) {
                hidden = it
                if (browser.state.value.showHidden != it) browser.toggleHidden()
            }
            SwitchRow(stringResource(R.string.ui_settings_grid), grid) { grid = it; browser.setGrid(it) }
            SwitchRow(stringResource(R.string.ui_folders_first), foldersFirst) { foldersFirst = it; settings.foldersFirst = it; browser.reload() }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { showSort = true }.padding(horizontal = Dim.Gutter + 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ui_default_sort), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    stringResource(when (sort.field) {
                        SortFieldCore.NAME -> R.string.ui_sort_name
                        SortFieldCore.MODIFIED -> R.string.ui_sort_modified
                        SortFieldCore.SIZE -> R.string.ui_sort_size
                        SortFieldCore.TYPE -> R.string.ui_sort_type
                    }),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Header(stringResource(R.string.ui_appearance))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { showTheme = true }.padding(horizontal = Dim.Gutter + 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ui_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    stringResource(when (ThemeManager.mode) {
                        ThemeManager.LIGHT -> R.string.ui_theme_light
                        ThemeManager.DARK -> R.string.ui_theme_dark
                        else -> R.string.ui_theme_system
                    }),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (ThemeManager.dynamicSupported) {
                SwitchRow(
                    stringResource(R.string.ui_dynamic_color), ThemeManager.dynamicColor,
                    supporting = stringResource(R.string.ui_dynamic_color_desc)
                ) { ThemeManager.saveDynamic(context, it) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Header(stringResource(R.string.ui_language))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { showLanguage = true }.padding(horizontal = Dim.Gutter + 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ui_language), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    stringResource(when (language) {
                        LanguageManager.ARABIC -> R.string.ui_language_ar
                        LanguageManager.ENGLISH -> R.string.ui_language_en
                        else -> R.string.ui_language_system
                    }),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Header(stringResource(R.string.ui_settings_about))
            Text(
                stringResource(R.string.ui_about_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dim.Gutter + 4.dp, vertical = 8.dp)
            )
        }
    }
    if (showTheme) {
        val options = listOf(
            ThemeManager.SYSTEM to R.string.ui_theme_system,
            ThemeManager.LIGHT to R.string.ui_theme_light,
            ThemeManager.DARK to R.string.ui_theme_dark
        )
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text(stringResource(R.string.ui_theme)) },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                showTheme = false
                                ThemeManager.saveMode(context, value)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = value == ThemeManager.mode, onClick = null)
                            Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showTheme = false }) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
    if (showLanguage) {
        val options = listOf(
            LanguageManager.SYSTEM to R.string.ui_language_system,
            LanguageManager.ARABIC to R.string.ui_language_ar,
            LanguageManager.ENGLISH to R.string.ui_language_en
        )
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text(stringResource(R.string.ui_language)) },
            text = {
                Column {
                    options.forEach { (tag, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                showLanguage = false
                                if (tag != language) {
                                    LanguageManager.save(context, tag)
                                    (context as? android.app.Activity)?.recreate()
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = tag == language, onClick = null)
                            Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showLanguage = false }) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
    if (showSort) SortSheet(sort, { sort = it; browser.setSort(it) }) { showSort = false }
}

@Composable
private fun Header(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dim.Gutter + 4.dp, end = Dim.Gutter, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }
            .padding(horizontal = Dim.Gutter + 4.dp, vertical = if (supporting != null) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onChange)
    }
}
