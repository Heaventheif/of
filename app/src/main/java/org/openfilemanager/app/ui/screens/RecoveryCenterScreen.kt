package org.openfilemanager.app.ui.screens

import org.openfilemanager.app.ui.components.kindIcon
import org.openfilemanager.app.ui.components.kindLabelRes
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Restore
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
import android.text.format.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppLog
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.FileOperationService
import org.openfilemanager.app.FeatureFlags
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer
import org.openfilemanager.app.recovery.RecoveryCenterModel
import org.openfilemanager.app.ui.LocalSnack
import org.openfilemanager.app.ui.components.EmptyState
import org.openfilemanager.app.ui.components.OfmSnackbarHost
import org.openfilemanager.app.ui.components.SnackKind
import org.openfilemanager.app.ui.components.show
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryCenterScreen(onBack: () -> Unit) {
    // بوابة العلم داخل نقطة دخول الشاشة نفسها (حماية مضاعفة فوق بوابة
    // التنقل وصف الإعدادات): مطفأةً يعني لا شاشة — رجوع فوري.
    if (!FeatureFlags.recoveryCenter) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val ctx = LocalContext.current
    val opsCenter = ctx.appContainer.opsCenter
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val model = remember { RecoveryCenterModel(ctx.appContainer.journal) }
    // null = تحميل جارٍ؛ الفحص على خيط IO كأي قراءة سجل.
    var listing by remember { mutableStateOf<RecoveryCenterModel.Listing?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var busyFor by remember { mutableStateOf<String?>(null) }
    var confirmDismissFor by remember { mutableStateOf<OpRecord?>(null) }

    LaunchedEffect(refreshKey) {
        listing = null
        val loaded = withContext(Dispatchers.IO) {
            model.load(activeOpIds = opsCenter.activeOps(), serviceBusy = opsCenter.hasActiveServiceWork())
        }
        // استئناف مُرسل في هذه العملية لا يُعرض ثانية (نافذة السباق — كما في OpsCenter).
        listing = loaded.copy(ops = loaded.ops.filterNot { opsCenter.isResumeDispatched(it.id) })
    }

    val failedText = stringResource(R.string.ui_recovery_failed)
    val dismissedText = stringResource(R.string.ui_recovery_center_dismissed)
    val resumedText = stringResource(R.string.ui_recovery_center_resumed)
    val rolledBackText = stringResource(R.string.ui_recovery_center_rolled_back)

    fun removeFromView(opId: String) {
        listing = listing?.let { it.copy(ops = it.ops.filterNot { e -> e.id == opId }) }
    }

    fun decide(op: OpRecord, resume: Boolean) {
        if (busyFor != null) return
        busyFor = op.id
        val opId = op.id
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                if (resume) model.resume(opId) { FileOperationService.enqueue(ctx, it) }
                else model.rollBack(opId) { FileOperationService.enqueue(ctx, it) }
            }
            busyFor = null
            if (err == null) {
                // نجاح القرار: خاتمة التسمية دُوّنت في السجل هنا والبقية صارت
                // ملك الخدمة — يُزال من القائمة، وتُحدَّث حالة حوار الاسترداد
                // المشترك (لا قرار مزدوج عليه).
                removeFromView(opId)
                if (resume) opsCenter.markResumeDispatched(opId) else opsCenter.dismissRecoverable(opId)
                opsCenter.requestRecoveryCheck()
                snack.show(if (resume) resumedText else rolledBackText, SnackKind.SUCCESS)
            } else {
                // فشل القرار: يبقى العنصر مدرجاً — لا اختفاء بلا استرداد مكتمل
                // أو إزالة مؤكدة، والخطأ يُعرض لا يُبتلع.
                AppLog.e("ops", "فشل قرار مركز الاسترداد للعملية $opId: $err")
                snack.show(failedText, SnackKind.ERROR)
            }
        }
    }

    fun removeConfirmed(op: OpRecord) {
        if (busyFor != null) return
        busyFor = op.id
        val opId = op.id
        scope.launch {
            // هنا وحده confirmed=true — مصدره حوار التأكيد الصريح لا غير.
            val err = withContext(Dispatchers.IO) {
                model.dismiss(opId, confirmed = true) { FileOperationService.enqueue(ctx, it) }
            }
            busyFor = null
            if (err == null) {
                removeFromView(opId)
                opsCenter.dismissRecoverable(opId)
                opsCenter.requestRecoveryCheck()
                snack.show(dismissedText, SnackKind.SUCCESS)
            } else {
                AppLog.e("ops", "فشل قرار مركز الاسترداد للعملية $opId: $err")
                snack.show(failedText, SnackKind.ERROR)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { OfmSnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_recovery_center)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) } }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val current = listing
            when {
                current == null -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.ui_recovery_center_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                current.error != null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.ui_recovery_center_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton({ refreshKey++ }) { Text(stringResource(R.string.ui_recovery_center_retry)) }
                }
                current.ops.isEmpty() -> EmptyState(
                    Icons.Outlined.Restore,
                    stringResource(R.string.ui_recovery_center_empty),
                    Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(current.ops, key = { it.id }) { op ->
                        val route = remember(op.id) { model.routeOf(op) }
                        RecoveryEntryRow(
                            op = op,
                            route = route,
                            busy = busyFor == op.id,
                            onResume = { decide(op, resume = true) },
                            onRollBack = { decide(op, resume = false) },
                            onDismissRequest = { confirmDismissFor = op }
                        )
                    }
                }
            }
        }
    }

    confirmDismissFor?.let { op ->
        AlertDialog(
            onDismissRequest = { confirmDismissFor = null },
            title = { Text(stringResource(R.string.ui_recovery_center_dismiss_title)) },
            text = { Text(stringResource(R.string.ui_recovery_center_dismiss_body)) },
            confirmButton = {
                TextButton({
                    confirmDismissFor = null
                    removeConfirmed(op)
                }) { Text(stringResource(R.string.ui_recovery_center_dismiss)) }
            },
            dismissButton = {
                TextButton({ confirmDismissFor = null }) { Text(stringResource(R.string.ui_cancel)) }
            }
        )
    }
}

/** زر الاستئناف: للتسمية «إكمال التسمية» وللبقية «استئناف» (كما في الحوار القائم). */
private fun resumeLabelRes(kind: OpKind): Int =
    if (kind == OpKind.RENAME) R.string.ui_recovery_complete_rename else R.string.ui_recovery_resume

/** زر التراجع: للتسمية «تراجع عن التسمية» وللبقية «إلغاء وتنظيف». */
private fun rollBackLabelRes(kind: OpKind): Int =
    if (kind == OpKind.RENAME) R.string.ui_recovery_revert_rename else R.string.ui_recovery_cancel

/** صف عملية منقطعة: أيقونة النوع (بوصف وصولي) + المسارات + العدّاد + التاريخ + الأزرار. */
@Composable
private fun RecoveryEntryRow(
    op: OpRecord,
    route: Pair<String, String>,
    busy: Boolean,
    onResume: () -> Unit,
    onRollBack: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val kindLabel = stringResource(kindLabelRes(op.kind))
    Column(Modifier.fillMaxWidth().padding(horizontal = Dim.Gutter + 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(op.kind), kindLabel, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(kindLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.ui_recovery_center_from_to,
                        BidiText.wrap(route.first),
                        BidiText.wrap(route.second)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.ui_recovery_center_counts, op.ok, op.failed, op.skipped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            DateUtils.getRelativeTimeSpanString(op.createdAt).toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row {
            TextButton(onResume, enabled = !busy) { Text(stringResource(resumeLabelRes(op.kind))) }
            TextButton(onRollBack, enabled = !busy) { Text(stringResource(rollBackLabelRes(op.kind))) }
            TextButton(onDismissRequest, enabled = !busy) { Text(stringResource(R.string.ui_recovery_center_dismiss)) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
