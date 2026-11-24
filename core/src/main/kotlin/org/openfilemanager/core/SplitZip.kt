package org.openfilemanager.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayList

/** نتيجة فحص أجزاء ZIP المقسم. */
sealed interface SplitZipCheck {
    /** ليس أرشيفاً مقسماً: ملف zip عادي بلا أجزاء .zNN. */
    data object NotSplit : SplitZipCheck

    /** مقسم وسليم: [parts] بترتيبها الصحيح (z01 … zNN ثم zip). */
    data class Split(val parts: List<Path>) : SplitZipCheck

    /** مقسم وناقص: [missing] الأجزاء الغائبة (تُعرض للمستخدم كما هي). */
    data class Incomplete(val missing: List<String>) : SplitZipCheck
}

/**
 * ZIP المقسم: التفكيك فقط لا الإنشاء.
 * الصيغة المدعومة: `name.z01 … name.zNN` ثم `name.zip` (جزء خاتمة).
 * التنفيذ: تُفحص الأجزاء كلها أولاً (لا بدء بناقص)، ثم تُدمج في ملف مؤقت داخل
 * وحدة الوجهة نفسها ويُستخرج عبر [ArchiveExtractor] ثم يُحذف الملف المؤقت.
 */
object SplitZip {
    /** بصمة جزء الخاتمة في ZIP المقسم (PK\u0007\u0008)؛ تُتخطى إن وُجدت عند الدمج. */
    private val SPANNING_MARKER = byteArrayOf(0x50, 0x4B, 0x07, 0x08)

    fun check(zip: Path): SplitZipCheck {
        val name = zip.fileName.toString()
        val stem = name.substringBeforeLast('.', "")
        if (stem.isEmpty()) return SplitZipCheck.NotSplit
        val parent = zip.parent ?: return SplitZipCheck.NotSplit

        var maxZ = 0
        for (i in 1..999) {
            if (Files.exists(parent.resolve(zName(stem, i)), LinkOption.NOFOLLOW_LINKS)) maxZ = i
        }
        var maxAlt = 0
        for (i in 1..999) {
            if (Files.exists(parent.resolve(altName(stem, i)), LinkOption.NOFOLLOW_LINKS)) maxAlt = i
        }
        if (maxZ == 0 && maxAlt == 0) return SplitZipCheck.NotSplit

        val parts = ArrayList<Path>()
        val missing = ArrayList<String>()
        for (i in 1..maxZ) {
            val part = parent.resolve(zName(stem, i))
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) parts.add(part)
            else missing.add(part.fileName.toString())
        }
        for (i in 1..maxAlt) {
            val part = parent.resolve(altName(stem, i))
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) parts.add(part)
            else missing.add(part.fileName.toString())
        }
        if (missing.isNotEmpty()) return SplitZipCheck.Incomplete(missing)
        return SplitZipCheck.Split(parts.sortedBy { partIndex(it) } + listOf(zip))
    }

    private fun zName(stem: String, index: Int) = "$stem.z" + index.toString().padStart(2, '0')

    private fun altName(stem: String, index: Int) = "$stem." + index.toString().padStart(3, '0')

    private fun partIndex(part: Path): Int {
        val name = part.fileName.toString()
        val zMatch = Regex("\\.z(\\d{2,3})$").find(name)
        if (zMatch != null) return zMatch.groupValues[1].toInt()
        val dotMatch = Regex("\\.(\\d{3})$").find(name)
        if (dotMatch != null) return dotMatch.groupValues[1].toInt()
        return Int.MAX_VALUE
    }

    /**
     * يستخرج أرشيفاً مقسماً إلى [destinationDir] عبر دمجه مؤقتاً داخل مجلد مؤقت
     * يحمل اسم الأرشيف الأصلي (حتى يكون اسم مجلد الاستخراج النهائي صحيحاً)،
     * ثم يُحذف المؤقت بعد النجاح أو الفشل.
     * [limits.maxTotalBytes] حد المساحة المتاحة (StatFs في طبقة أندرويد).
     */
    fun extract(
        zip: Path,
        destinationDir: Path,
        limits: ExtractLimits,
        charset: java.nio.charset.Charset = Charsets.UTF_8,
        onProgress: (entries: Int, bytes: Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
        filter: EntryFilter = EntryFilter.ALL,
        finalNameOverride: String? = null
    ): ExtractReport {
        val split = check(zip)
        require(split is SplitZipCheck.Split) { "أجزاء ناقصة أو غير مقسم: $split" }
        val parts = split.parts
        val combinedSize = parts.sumOf { sizeOf(it) }
        if (combinedSize > limits.maxTotalBytes) throw ArchiveLimitException("حجم الأجزاء يتجاوز المساحة المتاحة")

        val staging = destinationDir.resolve(".fm-merge-" + System.nanoTime())
        val merged = staging.resolve(zip.fileName.toString())
        try {
            Files.createDirectories(staging)
            Files.newOutputStream(merged).use { out -> mergeParts(parts, out, checkCancelled) }
            return ArchiveExtractor(limits, charset).extract(
                merged, destinationDir, onProgress, checkCancelled, filter,
                finalNameOverride = finalNameOverride
            )
        } finally {
            runCatching { AtomicIO.deleteTree(staging) }
        }
    }

    /**
     * يدمج الأجزاء في تدفق خارجي: لمعاينة محتويات الأرشيف المقسم قبل الاستخراج
     * دون كتابة في مجلد الوجهة.
     */
    fun mergeTo(parts: List<Path>, out: OutputStream, checkCancelled: () -> Unit = {}) =
        mergeParts(parts, out, checkCancelled)

    /**
     * يدمج الأجزاء بترتيبها. لا نستخدم mark/reset (بصمة جزء الخاتمة): ليست كل تدفق
     * يدعمها (تدفقات Jimfs لا تدعمها)، فنقرأ أول 4 بايتات من الجزء الأخير يدوياً.
     */
    private fun mergeParts(parts: List<Path>, out: OutputStream, checkCancelled: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        for ((index, part) in parts.withIndex()) {
            checkCancelled()
            Files.newInputStream(part).use { input ->
                if (index == parts.lastIndex) {
                    val head = ByteArray(4)
                    var read = 0
                    while (read < 4) {
                        val n = input.read(head, read, 4 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read in 1..3 || (read == 4 && !head.contentEquals(SPANNING_MARKER))) {
                        out.write(head, 0, read)
                    }
                }
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    private fun sizeOf(part: Path): Long =
        tryOrNull { Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size() }
            ?: throw IOException("تعذر قراءة الجزء: ${part.fileName}")
}
