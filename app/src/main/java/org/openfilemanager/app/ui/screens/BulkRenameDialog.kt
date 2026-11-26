package org.openfilemanager.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.BrowserViewModel
import org.openfilemanager.app.ui.BulkRenameContext
import org.openfilemanager.core.BatchRename
import org.openfilemanager.core.BatchRenameSpec
import org.openfilemanager.core.RenamePlan
import org.openfilemanager.core.RenameRow
import org.openfilemanager.core.RenameStatus

private const val TOKEN_NAME = "%%"
private const val TOKEN_NUMBER = "##"

/**
 * Bulk rename: template with quick-insert chips, global extension, numbering options (shown only when the
 * template has a number token) and a live preview. Preview and execution both come from [BatchRename.plan].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BulkRenameDialog(vm: BrowserViewModel, onConfirm: (BatchRenameSpec) -> Unit, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf<BulkRenameContext?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        data = vm.bulkRenameContext()
        loaded = true
        if (data == null) onDismiss()
    }
    var template by remember { mutableStateOf(TextFieldValue(TOKEN_NAME, TextRange(TOKEN_NAME.length))) }
    var ext by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf("1") }
    var zerosText by remember { mutableStateOf("") }

    val numbered = template.text.contains(TOKEN_NUMBER)
    val start = if (numbered) BatchRename.parseStart(startText) else 1L
    val zeros = if (numbered) BatchRename.parseZeros(zerosText) else 0
    val spec = if (start != null && zeros != null) BatchRenameSpec(template.text, ext, start, zeros) else null
    val context = data
    // Recomputed off the main thread, debounced; a newer edit cancels the previous run.
    val plan by produceState<RenamePlan?>(null, context, spec) {
        if (context == null || spec == null) { value = null; return@produceState }
        delay(120)
        value = withContext(Dispatchers.Default) { BatchRename.plan(context.inputs, spec, context.existing, context.strictFat) }
    }
    val current = plan

    fun insert(token: String) {
        val sel = template.selection
        template = TextFieldValue(template.text.replaceRange(sel.min, sel.max, token), TextRange(sel.min + token.length))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp).heightIn(max = 720.dp)
        ) {
            Column(Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.ui_bulk_rename), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                template, { template = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.ui_bulk_template)) },
                                supportingText = { Text(stringResource(R.string.ui_bulk_template_hint)) }
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SuggestionChip({ insert(TOKEN_NAME) }, { Text(stringResource(R.string.ui_bulk_tok_name)) })
                                SuggestionChip({ insert(TOKEN_NUMBER) }, { Text(stringResource(R.string.ui_bulk_tok_number)) })
                                SuggestionChip({ insert("_") }, { Text("_") })
                                SuggestionChip({ insert("-") }, { Text("-") })
                                SuggestionChip({ insert(" ") }, { Text("␣") })
                            }
                            OutlinedTextField(
                                ext, { ext = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.ui_bulk_ext)) },
                                placeholder = { Text("png") },
                                supportingText = { Text(stringResource(R.string.ui_bulk_ext_hint)) }
                            )
                            if (numbered) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        startText, { startText = it.filter { c -> c in '0'..'9' }.take(18) }, singleLine = true,
                                        modifier = Modifier.weight(1f), isError = start == null,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        label = { Text(stringResource(R.string.ui_bulk_start)) },
                                        supportingText = { Text(stringResource(R.string.ui_bulk_start_hint)) }
                                    )
                                    OutlinedTextField(
                                        zerosText, { zerosText = it.filter { c -> c in '0'..'9' }.take(2) }, singleLine = true,
                                        modifier = Modifier.weight(1f), isError = zeros == null,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        label = { Text(stringResource(R.string.ui_bulk_zeros)) },
                                        supportingText = { Text(stringResource(R.string.ui_bulk_zeros_hint)) }
                                    )
                                }
                            }
                        }
                    }
                    stickyHeader { PreviewSummary(current, loading = !loaded || (spec != null && current == null)) }
                    if (current != null) {
                        items(current.rows.size, key = { current.rows[it].index }) { i ->
                            BulkPreviewRow(current.rows[i])
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) }
                    Button({ spec?.let(onConfirm) }, enabled = current?.canApply == true) { Text(stringResource(R.string.ui_bulk_apply)) }
                }
            }
        }
    }
}

@Composable
private fun PreviewSummary(plan: RenamePlan?, loading: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(Modifier.padding(top = 8.dp), color = scheme.outlineVariant)
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    plan != null -> {
                        Text(
                            stringResource(R.string.ui_bulk_summary, plan.changedCount, plan.unchangedCount, plan.blockingCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                plan.blockingCount > 0 -> scheme.error
                                plan.changedCount > 0 -> scheme.primary
                                else -> scheme.onSurfaceVariant
                            }
                        )
                        if (plan.changedCount == 0 && plan.blockingCount == 0) {
                            Text(stringResource(R.string.ui_bulk_nothing), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider(color = scheme.outlineVariant)
        }
    }
}

/** One preview row: status icon, then the old name (struck through when it will change) above the new name. */
@Composable
private fun BulkPreviewRow(row: RenameRow) {
    val scheme = MaterialTheme.colorScheme
    val changed = row.status == RenameStatus.OK
    val (icon, tint) = when {
        row.blocking -> Icons.Outlined.ErrorOutline to scheme.error
        changed -> Icons.Outlined.CheckCircle to scheme.primary
        else -> Icons.Outlined.RemoveCircleOutline to scheme.outline
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (row.status == RenameStatus.UNCHANGED) {
                Text(
                    BidiText.wrap(row.original), style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    BidiText.wrap(row.original), style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (changed) TextDecoration.LineThrough else null
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        BidiText.wrap(row.newName), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = if (row.blocking) scheme.error else scheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
                val note = when (row.status) {
                    RenameStatus.DUPLICATE -> R.string.ui_bulk_st_dup
                    RenameStatus.CONFLICT -> R.string.ui_bulk_st_conflict
                    RenameStatus.INVALID -> R.string.ui_bulk_st_invalid
                    else -> null
                }
                if (note != null) Text(stringResource(note), style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }
        }
    }
}
