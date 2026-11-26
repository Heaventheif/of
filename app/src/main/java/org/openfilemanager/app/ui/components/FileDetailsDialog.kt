package org.openfilemanager.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.FileEntry
import org.openfilemanager.app.R
import org.openfilemanager.app.properties
import java.io.File
import java.text.DateFormat
import java.util.Date

/** ما يُحسب خارج الخيط الرئيسي لنافذة التفاصيل. */
private sealed interface DetailsModel {
    data object Loading : DetailsModel
    data class Single(
        val name: String,
        val fullPath: String,
        val parent: String,
        val type: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val modified: Long,
        val createdAt: Long?,
        val dims: String?,
        val durationMs: Long?,
        val canRead: Boolean,
        val canWrite: Boolean,
        /** للمجلد: عدد العناصر المباشرة. */
        val children: Int?
    ) : DetailsModel
    data class Multi(val count: Int, val totalBytes: Long, val paths: List<String>) : DetailsModel
    data object Missing : DetailsModel
}

/** حجم المجلد تراكمياً بسقف على عدد العناصر كي لا يطول الحساب على مجلدات ضخمة. */
private fun treeSize(f: File, budget: IntArray): Long {
    if (!f.isDirectory) return f.length()
    var total = 0L
    val kids = f.listFiles() ?: return 0L
    for (k in kids) {
        if (budget[0]-- <= 0) return total
        total += treeSize(k, budget)
    }
    return total
}

private fun load(paths: List<String>): DetailsModel {
    val files = paths.map { File(it) }.filter { it.exists() }
    if (files.isEmpty()) return DetailsModel.Missing
    if (files.size > 1) {
        val budget = intArrayOf(200_000)
        return DetailsModel.Multi(files.size, files.sumOf { treeSize(it, budget) }, files.map { it.absolutePath })
    }
    val f = files.single()
    val entry = FileEntry(f.name, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified(), f.toPath())
    val p = entry.properties()
    val size = if (f.isDirectory) treeSize(f, intArrayOf(200_000)) else f.length()
    return DetailsModel.Single(
        name = f.name,
        fullPath = f.absolutePath,
        parent = f.parent ?: "",
        type = p.type,
        isDir = f.isDirectory,
        sizeBytes = size,
        modified = p.modified,
        createdAt = p.createdAt,
        dims = if (p.imageWidth != null && p.imageHeight != null) "${p.imageWidth} × ${p.imageHeight}" else null,
        durationMs = p.durationMs,
        canRead = p.canRead,
        canWrite = p.canWrite,
        children = if (f.isDirectory) f.list()?.size else null
    )
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("path", text))
    Toast.makeText(ctx, ctx.getString(R.string.ui_path_copied), Toast.LENGTH_SHORT).show()
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // القيم قابلة للتحديد والنسخ، والمسارات تُلفّ بعلامات اتجاه كي لا تنقلب الشرطات والنقاط.
        SelectionContainer { Text(BidiText.wrap(value), style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * نافذة «التفاصيل»: لعنصر واحد تعرض الاسم والمسار الكامل والمجلد والنوع والحجم والتواريخ
 * (وأبعاد الصورة / مدة الوسائط / الصلاحيات حيث تنطبق)، ولعدة عناصر العدد والحجم الكلي
 * وقائمة المسارات الكاملة. زر «نسخ المسار» ينسخ المسار (أو كل المسارات سطراً لكل مسار).
 */
@Composable
fun FileDetailsDialog(paths: List<String>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val model by produceState<DetailsModel>(DetailsModel.Loading, paths) {
        value = withContext(Dispatchers.IO) { runCatching { load(paths) }.getOrDefault(DetailsModel.Missing) }
    }
    val dateFmt = DateFormat.getDateTimeInstance()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (val m = model) {
                    is DetailsModel.Single -> BidiText.wrap(m.name)
                    is DetailsModel.Multi -> stringResource(R.string.ui_selected_count, m.count)
                    else -> stringResource(R.string.ui_details)
                },
                maxLines = 2
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (val m = model) {
                    DetailsModel.Loading -> Text("…")
                    DetailsModel.Missing -> Text(stringResource(R.string.ui_detail_unavailable))
                    is DetailsModel.Single -> {
                        DetailRow(stringResource(R.string.ui_detail_full_path), m.fullPath)
                        DetailRow(stringResource(R.string.ui_detail_path), m.parent)
                        DetailRow(stringResource(R.string.ui_detail_type), m.type)
                        DetailRow(
                            stringResource(if (m.isDir) R.string.ui_detail_total_size else R.string.ui_detail_size),
                            Formatter.formatFileSize(ctx, m.sizeBytes) + " (" + "%,d".format(m.sizeBytes) + " B)"
                        )
                        m.children?.let { DetailRow(stringResource(R.string.ui_detail_items), it.toString()) }
                        m.dims?.let { DetailRow(stringResource(R.string.ui_detail_dims), "\u2066$it\u2069") }
                        m.durationMs?.let { DetailRow(stringResource(R.string.ui_detail_duration), formatDuration(it)) }
                        DetailRow(stringResource(R.string.ui_detail_date), dateFmt.format(Date(m.modified)))
                        m.createdAt?.let { DetailRow(stringResource(R.string.ui_detail_created), dateFmt.format(Date(it))) }
                        DetailRow(
                            stringResource(R.string.ui_detail_perms),
                            stringResource(
                                when {
                                    m.canRead && m.canWrite -> R.string.ui_detail_perm_rw
                                    m.canRead -> R.string.ui_detail_perm_r
                                    else -> R.string.ui_detail_perm_none
                                }
                            )
                        )
                    }
                    is DetailsModel.Multi -> {
                        DetailRow(stringResource(R.string.ui_detail_items), m.count.toString())
                        DetailRow(stringResource(R.string.ui_detail_total_size), Formatter.formatFileSize(ctx, m.totalBytes))
                        DetailRow(stringResource(R.string.ui_detail_full_path), m.paths.joinToString("\n"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_ok)) } },
        dismissButton = {
            val text = when (val m = model) {
                is DetailsModel.Single -> m.fullPath
                is DetailsModel.Multi -> m.paths.joinToString("\n")
                else -> null
            }
            if (text != null) TextButton({ copyToClipboard(ctx, text) }) { Text(stringResource(R.string.ui_copy_path)) }
        }
    )
}
