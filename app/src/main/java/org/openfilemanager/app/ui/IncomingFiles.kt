package org.openfilemanager.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/** Copies files shared/opened from other apps into app cache (bounded), so the rest of the app works with plain paths. */
object IncomingFiles {
    private const val MAX_FILES = 50
    private const val MAX_BYTES = 512L * 1024 * 1024

    private const val TOTAL_CACHE_BUDGET = 2L * 1024 * 1024 * 1024

    @Suppress("DEPRECATION")
    fun uris(intent: Intent): List<Uri> {
        val found = LinkedHashMap<String, Uri>()
        fun add(u: Uri?) { if (u != null && (u.scheme == "content" || u.scheme == "file")) found.putIfAbsent(u.toString(), u) }
        when (intent.action) {
            Intent.ACTION_VIEW -> add(intent.data)
            Intent.ACTION_SEND -> add(runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull())
            Intent.ACTION_SEND_MULTIPLE -> runCatching { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }
                .getOrNull().orEmpty().forEach(::add)
        }
        runCatching { intent.clipData?.let { c -> for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) } }
        return found.values.toList()
    }

    /** (مراجعة 0.8.6-#1) النشاط مُصدَّر ويقبل VIEW/SEND بـ file://، فأي تطبيق يستطيع أن يجعلنا ننسخ أي ملف مقروء إلى الكاش. */
    fun isSharedStoragePath(path: String): Boolean = path.startsWith("/storage/")

    /** صحّة منطقية خالصة للاختبار: هل [path] داخل أحد الجذور الخاصة (حدود شاملة للنهاية، لا بادئات خادعة)؟ */
    fun isPrivatePath(path: String, privateRoots: Collection<String>): Boolean {
        if (path.isBlank()) return true
        return privateRoots.any { root ->
            val r = root.trimEnd('/')
            path == r || path.startsWith("$r/")
        }
    }

    /** جذور التطبيق الخاصة لحظة النسخ، مطبَّعة بعد حلّ الروابط الرمزية (/data/data ليست إلا رابطاً لـ /data/user/0). */
    private fun privateRoots(ctx: Context): List<String> =
        // externalCacheDir.parentFile = Android/data/<pkg> itself, so its other subfolders are covered too, plus obb.
        listOf(
            ctx.dataDir, ctx.filesDir, ctx.cacheDir, ctx.codeCacheDir, ctx.noBackupFilesDir,
            ctx.externalCacheDir, ctx.externalCacheDir?.parentFile, ctx.getExternalFilesDir(null), ctx.obbDir
        )
            .filterNotNull()
            .map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

    /** يحذف مجلدات incoming_* الأقدم من يوم: كانت تتراكم (حتى 512MB لكل مشاركة) ولا يحذفها أحد. */
    private fun purgeStale(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        ctx.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("incoming_") && f.lastModified() < cutoff }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun sizeOf(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * يفرض الحد الإجمالي: يحذف مجلدات incoming_* الأقدم أولاً حتى ينزل
     * الإجمالي (بما فيه [extraBytes] للطلب القادم) تحت الميزانية.
     */
    private fun enforceTotalBudget(ctx: Context, extraBytes: Long, keep: File? = null) {
        val dirs = ctx.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("incoming_") && f != keep }
            ?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { runCatching { sizeOf(it) }.getOrDefault(0L) } + extraBytes
        for (old in dirs) {
            if (total <= TOTAL_CACHE_BUDGET) break
            val freed = runCatching { sizeOf(old) }.getOrDefault(0L)
            if (runCatching { old.deleteRecursively(); true }.getOrDefault(false)) {
                total -= freed
            }
        }
    }

    /** Blocking; call off the main thread. Returns the staged files, or throws IOException with a short reason. */
    fun stage(ctx: Context, uris: List<Uri>): List<File> {
        if (uris.size > MAX_FILES) throw IOException("limit")
        purgeStale(ctx)
        // الحد الإجمالي قبل بدء النسخ: إن كان الكاش ممتلئاً بمشاركات قديمة
        // فأمسح الأقدم بدل أن يفشل هذا الطلب أو يفيض التخزين.
        enforceTotalBudget(ctx, extraBytes = 0L)
        val dir = File(ctx.cacheDir, "incoming_${UUID.randomUUID()}").also { if (!it.mkdirs()) throw IOException("cache") }
        try {
            var total = 0L
            val out = ArrayList<File>()
            for (uri in uris) {
                var name: String? = null
                if (uri.scheme == "content") runCatching {
                    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0)
                    }
                }
                val safe = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file")
                    .replace(Regex("[\\\\/:*?\"<>|\u0000]"), "_").trim().ifEmpty { "file" }.let { s ->
                        // Truncate by UTF-8 bytes (limit is 255 bytes), leaving room for " (n)"
                        var end = minOf(s.length, 120)
                        while (end > 0 && s.substring(0, end).toByteArray(Charsets.UTF_8).size > 200) end--
                        s.substring(0, end).ifEmpty { "file" }
                    }
                    .let { if (it == "." || it == "..") "file" else it }
                // توحيد الاسم الفريد عبر NameRules.uniqueName — كان مكرراً.
                // Dotfiles (".gitignore") keep their whole name as the stem: نفس سلوك
                // NameRules (dot > 0 لا dot == 0).
                val unique = org.openfilemanager.core.NameRules.uniqueName(safe) { c -> File(dir, c).exists() }
                val target = File(dir, unique)
                val input = when (uri.scheme) {
                    "content" -> ctx.contentResolver.openInputStream(uri)
                    "file" -> uri.path?.takeIf { p ->
                        val canon = runCatching { File(p).canonicalPath }.getOrDefault(p)
                        isSharedStoragePath(canon) && !isPrivatePath(canon, privateRoots(ctx))
                    }?.let { java.io.FileInputStream(it) }
                    else -> null
                } ?: throw IOException("read")
                input.use { ins ->
                    target.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = ins.read(buf); if (r < 0) break
                            total += r
                            if (total > MAX_BYTES) throw IOException("size")
                            os.write(buf, 0, r)
                        }
                    }
                }
                out += target
            }
            // الحد الإجمالي بعد النسخ أيضاً: هذا الطلب وحده قد يجاوز الميزانية إن كانت المشاركة ضخمة؛
            if (total > TOTAL_CACHE_BUDGET) throw IOException("size")
            enforceTotalBudget(ctx, extraBytes = total, keep = dir)
            return out
        } catch (t: Throwable) {
            runCatching { dir.deleteRecursively() }
            throw t
        }
    }
}
