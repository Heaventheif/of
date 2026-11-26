package org.openfilemanager.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import org.openfilemanager.app.R
import org.openfilemanager.app.appContainer

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * getUriForFile يرمي IllegalArgumentException لملفات خارج الجذور المصرَّح
 * بها في file_paths.xml (مثلاً بطاقة SD قابلة للإزالة) — كل النداة هنا تحمي
 * النداء بـ runCatching وتُبلغ المستخدم، كالتوأم في FileFeatures.ShareIntents.
 */
private fun uriFor(ctx: Context, path: String): Uri =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))

fun mimeOf(path: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension.lowercase()) ?: "*/*"

/** إطلاق نية مع إبلاغ المستخدم عند الفشل: لا ابتلاع صامت لاستثناءات التطبيقات الخارجية. */
private fun launchOrTell(
    ctx: Context,
    intent: Intent,
    noAppMessage: String = ctx.getString(R.string.ui_err_no_app),
    failedMessage: String = ctx.getString(R.string.ui_err_open_failed)
): Boolean = try {
    ctx.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    Toast.makeText(ctx, noAppMessage, Toast.LENGTH_SHORT).show()
    false
} catch (e: Exception) {
    // فشل تشغيل Intent (انتهاء صلاحية URI، رفض المستلم، حالة النظام): رسالة
    // مفهومة بدل الصمت — دون كشف مسارات داخلية.
    Toast.makeText(ctx, failedMessage, Toast.LENGTH_SHORT).show()
    false
}

/** ClipData carries the name and lets the share sheet draw a real preview instead of a generic icon. */
fun shareFiles(ctx: Context, paths: List<String>) {
    if (paths.isEmpty()) return
    // ملف خارج الجذور المصرَّح بها (بطاقة قابلة للإزالة) لا يُشارَك:
    // رسالة مفهومة بدل انهيار FileProvider.getUriForFile.
    val uris = ArrayList<Uri>(paths.size)
    paths.forEach { p ->
        val u = runCatching { uriFor(ctx, p) }.getOrNull()
        if (u == null) {
            Toast.makeText(ctx, ctx.getString(R.string.ui_err_open_failed), Toast.LENGTH_SHORT).show()
            return
        }
        uris.add(u)
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            .putExtra(Intent.EXTRA_TITLE, File(paths[0]).name).setType(mimeOf(paths[0]))
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    }
    intent.clipData = ClipData.newUri(ctx.contentResolver, File(paths[0]).name, uris[0]).also { clip ->
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launchOrTell(ctx, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Opens in another app. [mime] overrides the guess ("Open as video/text/..."). Always shows the
 * chooser. Returns false (with a user-visible message) when no app can handle it or the launch
 * fails — the caller may use the result, but does not have to.
 */
fun openWithSystem(ctx: Context, path: String, mime: String? = null): Boolean {
    ctx.appContainer.lastOpened.record(File(path).toPath())
    val uri = runCatching { uriFor(ctx, path) }.getOrNull()
    if (uri == null) {
        Toast.makeText(ctx, ctx.getString(R.string.ui_err_open_failed), Toast.LENGTH_SHORT).show()
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: mimeOf(path))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return launchOrTell(ctx, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openUriWithSystem(ctx: Context, uri: Uri, mime: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return launchOrTell(ctx, intent)
}

/**
 * Starts the system installer for an APK. When "install unknown apps" is not allowed for this app yet,
 * opens that settings page and returns false so the caller can tell the user what to do.
 * A failed installer launch (bad URI, revoked grant) is reported to the user here.
 */
fun installApk(ctx: Context, path: String): Boolean {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        val opened = launchOrTell(
            ctx,
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            failedMessage = ctx.getString(R.string.ui_err_install_failed)
        )
        return !opened // false = "permission flow started; tell the user" (historical contract)
    }
    val uri = runCatching { uriFor(ctx, path) }.getOrNull()
    if (uri == null) {
        Toast.makeText(ctx, ctx.getString(R.string.ui_err_install_failed), Toast.LENGTH_SHORT).show()
        return true // فشل مُبلَّغ هنا؛ true كي لا يعرض المستدعي رسالة الإعدادات
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    // فشل الإطلاق نفسه يُبلَّغ هنا (Toast)؛ والقيمة المعادة true كي لا يفسرها
    // المستدعي «الصلاحية ناقصة» فيعرض رسالة الإعدادات الخطأ — عقد الدالة:
    // false = مسار منح صلاحية التثبيت فقط.
    launchOrTell(
        ctx, intent,
        noAppMessage = ctx.getString(R.string.ui_err_no_app),
        failedMessage = ctx.getString(R.string.ui_err_install_failed)
    )
    return true
}
