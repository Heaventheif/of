package org.openfilemanager.app.ui.screens

import org.openfilemanager.app.ui.components.kindIcon
import org.openfilemanager.app.ui.components.kindLabelRes
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.FeatureFlags
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.history.OperationHistoryEntry
import org.openfilemanager.app.history.OperationOutcomeState
import org.openfilemanager.app.ui.LocalSnack
import org.openfilemanager.app.ui.components.EmptyState
import org.openfilemanager.app.ui.components.OfmSnackbarHost
import org.openfilemanager.app.ui.components.SnackKind
import org.openfilemanager.app.ui.components.show
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.OpKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationHistoryScreen(onBack: () -> Unit) {
    // بوابة العلم داخل نقطة دخول الشاشة نفسها (حماية مضاعفة فوق بوابة
    // التنقل وصف الإعدادات): مطفأةً يعني لا شاشة — رجوع فوري.
    if (!FeatureFlags.operationHistory) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val store = remember { ctx.appContainer.opsCenter.operationHistory }
    // null = تحميل جارٍ؛ القراءة على خيط IO كأي قراءة مخزن.
    var listing by remember { mutableStateOf<List<OperationHistoryEntry>?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        listing = null
        listing = withContext(Dispatchers.IO) { store.entries() }
    }

    val clearedText = stringResource(R.string.ui_op_history_cleared)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { OfmSnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_op_history)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) } },
                actions = {
                    // زر المسح دائماً معروض؛ المسار الفعلي لا يبدأ إلا بعد تأكيد صريح.
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.ui_op_history_clear))
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val current = listing
            when {
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                current.isEmpty() -> EmptyState(
                    Icons.Outlined.History,
                    stringResource(R.string.ui_op_history_empty),
                    Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    // الأحدث أولاً (السجل مخزَّن الأقدم أولاً)؛ القائمة ساكنة فالمفاتيح الموضعية كافية.
                    itemsIndexed(current.asReversed()) { _, entry ->
                        HistoryEntryRow(entry)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ui_op_history_clear_title)) },
            text = { Text(stringResource(R.string.ui_op_history_clear_body)) },
            confirmButton = {
                TextButton({
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { store.clear() }
                        refreshKey++
                        snack.show(clearedText, SnackKind.SUCCESS)
                    }
                }) { Text(stringResource(R.string.ui_op_history_clear)) }
            },
            dismissButton = {
                TextButton({ confirmClear = false }) { Text(stringResource(R.string.ui_cancel)) }
            }
        )
    }
}

/** تسمية النوع المحلية — نوع مجهول (استئناف بلا سجل أصلي) له تسمية عامة. */
private fun kindOfName(name: String?): OpKind? =
    name?.let { n -> runCatching { OpKind.valueOf(n) }.getOrNull() }

private fun outcomeLabelRes(state: OperationOutcomeState): Int = when (state) {
    OperationOutcomeState.COMPLETED -> R.string.ui_op_history_outcome_completed
    OperationOutcomeState.PARTIAL -> R.string.ui_op_history_outcome_partial
    OperationOutcomeState.FAILED -> R.string.ui_op_history_outcome_failed
    OperationOutcomeState.CANCELLED -> R.string.ui_op_history_outcome_cancelled
}

/** صف خاتمة عملية: أيقونة النوع (بوصف وصولي) + الحالة + العدّاد + الوقت النسبي. */
@Composable
private fun HistoryEntryRow(entry: OperationHistoryEntry) {
    val kind = kindOfName(entry.kind)
    val kindLabel = stringResource(kindLabelRes(kind))
    val outcomeLabel = stringResource(outcomeLabelRes(entry.state))
    Column(Modifier.fillMaxWidth().padding(horizontal = Dim.Gutter + 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(kind), kindLabel, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(kindLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    outcomeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.state == OperationOutcomeState.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.ui_recovery_center_counts, entry.ok, entry.failed, entry.skipped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            DateUtils.getRelativeTimeSpanString(entry.finishedAt).toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
