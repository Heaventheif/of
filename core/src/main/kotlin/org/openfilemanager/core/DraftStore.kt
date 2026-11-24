package org.openfilemanager.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * مسودات محرر النصوص: تُحفظ تلقائياً كل بضع ثوانٍ عند وجود تعديلات،
 * وتُحذف بعد الحفظ، وتُعرض عند الفتح التالي إن بقيت (حماية من موت العملية).
 *
 * ملف لكل مسودة في مجلد `drafts` باسم <هاش مسار الملف>.draft، ومحتواه رأسٌ
 * (مسار الملف الأصلي المشفر) ثم فاصل سطر ثم نص المسودة؛ فالمسودة قابلة للاسترداد
 * حتى لو تغيّر الاسم أو أعيد تشغيل الجهاز.
 */
class DraftStore(private val dir: Path) {

    init {
        Files.createDirectories(dir)
    }

    /** مفتاح المسودة: SHA-256 قصير لمسار الملف (لا يُخزَّن المسار كاسم ملف). */
    fun keyOf(filePath: String): String =
        MessageDigest.getInstance("SHA-256").digest(filePath.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun draftPath(key: String): Path = dir.resolve("$key.draft")

    /** يكتب المسودة ذرياً (الحفظ الآمن بمرحلتين). */
    fun save(filePath: String, text: String) {
        val payload = filePath + "\n\u0000\n" + text
        AtomicIO.atomicWrite(draftPath(keyOf(filePath))) { out ->
            out.write(payload.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** يعيد نص المسودة إن وُجدت لهذا الملف. */
    fun load(filePath: String): String? {
        val f = draftPath(keyOf(filePath))
        if (!Files.exists(f)) return null
        return runCatching {
            val content = String(Files.readAllBytes(f), StandardCharsets.UTF_8)
            val sep = content.indexOf("\n\u0000\n")
            if (sep < 0) null
            else {
                val storedPath = content.substring(0, sep)
                if (storedPath != filePath) null else content.substring(sep + 3)
            }
        }.getOrNull()
    }

    /** تعليم المسودة وقت فتحها: تُعرض «مسودة محفوظة» إن مضت عليها مدة دون حفظ. */
    fun remove(filePath: String) {
        Files.deleteIfExists(draftPath(keyOf(filePath)))
    }

    fun exists(filePath: String): Boolean = Files.exists(draftPath(keyOf(filePath)))

    /** كل المسودات المعلقة (لعرض عددها أو تنظيفها). يعيد أزواج (مسار الملف الأصلي، النص). */
    fun pending(): List<Pair<String, String>> {
        if (!Files.isDirectory(dir)) return emptyList()
        val drafts = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".draft") }.toList()
        }
        return drafts.mapNotNull { f ->
            runCatching {
                val content = String(Files.readAllBytes(f), StandardCharsets.UTF_8)
                val sep = content.indexOf("\n\u0000\n")
                if (sep < 0) null
                else content.substring(0, sep) to content.substring(sep + 3)
            }.getOrNull()
        }
    }

    /** حذف المسودات الأقدم من [maxAgeMillis] (تنظيف دوري عند الإقلاع). يعيد عدد المحذوف. */
    fun prune(maxAgeMillis: Long): Int {
        if (!Files.isDirectory(dir)) return 0
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        var removed = 0
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".draft") }.forEach { f ->
                if (Files.getLastModifiedTime(f).toMillis() < cutoff) {
                    if (runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) removed++
                }
            }
        }
        return removed
    }
}
