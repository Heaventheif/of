package org.openfilemanager.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** عنصر واحد في معاينة محتويات الأرشيف (العرض قبل الاستخراج). */
data class ArchiveEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    /** الحجم غير المضغوط بالبايت؛ ‎-1 غير معلوم (GZ/XZ قبل فك الضغط). */
    val size: Long,
    /** الحجم المضغوط داخل الأرشيف؛ ‎-1 غير معلوم. */
    val compressedSize: Long,
    /** آخر تعديل بالميلي ثانية؛ ‎-1 غير معلوم. */
    val modifiedAt: Long,
    /** رابط رمزي/صلب أو ملف جهاز: يُتجاهل عند الاستخراج دائماً. */
    val isLink: Boolean,
    /** مسار غير آمن (Zip Slip أو اسم ممنوع): استخراجه يوقف العملية كلها. */
    val unsafe: Boolean
)

/** نتيجة المعاينة: العناصر (بسقف)، وإن قُطعت القائمة لكبرها، وعدد المقروء فعلاً. */
data class ArchiveListing(
    val entries: List<ArchiveEntryInfo>,
    val truncated: Boolean,
    val scanned: Int
)

/**
 * معاينة محتويات الأرشيف قبل الاستخراج: قراءة العناوين فقط بلا فك ضغط
 * ولا كتابة على القرص. تُكشف الصيغة ببصمة المحتوى (ZIP عبر فهرس ZIP المركزي،
 * وTAR ببث العناوين)، ويُعلَّم غير الآمن عبر [SafeResolve] نفسه الذي يطبقه المستخرج،
 * فلا يمكن أن تعرض المعاينة عنصراً سيقبلُه المستخرج ملغياً.
 */
object ArchiveInspector {

    /** جذر شكلي لفحص أمان الأسماء: [SafeResolve.resolve] عمليات مسار صرفة لا تلمس القرص. */
    private val probeRoot: Path = Paths.get("/")

    fun list(
        archive: Path,
        charset: Charset = Charsets.UTF_8,
        maxEntries: Int = 5_000,
        maxStreamBytes: Long = DEFAULT_MAX_STREAM_BYTES
    ): ArchiveListing {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxStreamBytes > 0L) { "maxStreamBytes must be positive" }
        return when (ArchiveFormat.detect(archive)) {
            ArchiveFormat.Kind.ZIP -> listZip(archive, charset, maxEntries)
            ArchiveFormat.Kind.TAR -> listTar(plain(archive), charset, maxEntries, maxStreamBytes)
            ArchiveFormat.Kind.TAR_GZ -> listTar(gz(archive), charset, maxEntries, maxStreamBytes)
            ArchiveFormat.Kind.TAR_XZ -> listTar(xz(archive), charset, maxEntries, maxStreamBytes)
            ArchiveFormat.Kind.GZ, ArchiveFormat.Kind.XZ -> listSingle(archive)
        }
    }

    // ---------- ZIP: فهرس مركزي بلا فك ضغط ----------

    private fun listZip(archive: Path, charset: Charset, maxEntries: Int): ArchiveListing {
        ZipFile(archive, charset.name()).use { zip ->
            val all = zip.entries
            val entries = ArrayList<ArchiveEntryInfo>(minOf(maxEntries, 256))
            var scanned = 0
            var truncated = false
            while (all.hasMoreElements()) {
                val e = all.nextElement()
                scanned++
                if (entries.size >= maxEntries) { truncated = true; break }
                entries += info(e.name, e.isDirectory, e.size, e.compressedSize, e.time, e.isUnixSymlink)
            }
            return ArchiveListing(entries, truncated, scanned)
        }
    }

    // ---------- TAR: بث العناوين ----------

    private fun listTar(
        open: InputStream,
        charset: Charset,
        maxEntries: Int,
        maxStreamBytes: Long
    ): ArchiveListing {
        open.use { raw ->
            val bounded = PreviewLimitInputStream(raw, maxStreamBytes)
            TarArchiveInputStream(BufferedInputStream(bounded, 64 * 1024), charset.name()).use { tar ->
                val entries = ArrayList<ArchiveEntryInfo>(minOf(maxEntries, 256))
                var scanned = 0
                var truncated = false
                while (true) {
                    val e = tar.nextEntry ?: break
                    scanned++
                    if (entries.size >= maxEntries) { truncated = true; break }
                    entries += info(
                        e.name, e.isDirectory, e.size, -1L, e.modTime.time,
                        e.isSymbolicLink || e.isLink || e.isCharacterDevice || e.isBlockDevice || e.isFIFO
                    )
                }
                return ArchiveListing(entries, truncated, scanned)
            }
        }
    }

    // ---------- GZ/XZ المنفرد ----------

    private fun listSingle(archive: Path): ArchiveListing {
        val attrs = Files.readAttributes(archive, BasicFileAttributes::class.java)
        val entry = ArchiveEntryInfo(
            name = ArchiveFormat.singleFileName(archive.fileName.toString()),
            isDirectory = false,
            size = -1L,
            compressedSize = attrs.size(),
            modifiedAt = attrs.lastModifiedTime().toMillis(),
            isLink = false,
            unsafe = false
        )
        return ArchiveListing(listOf(entry), truncated = false, scanned = 1)
    }

    // ---------- مساعدات ----------

    private fun info(
        name: String,
        isDirectory: Boolean,
        size: Long,
        compressedSize: Long,
        modifiedAt: Long,
        isLink: Boolean
    ): ArchiveEntryInfo {
        // علامة الجذر (`""`/`.`/`./` — أرشيفات `tar -czf x.tgz .`) ليست عنصراً
        // ولا مساراً غير آمن: تُعرض علامة جذر فقط بلا إنذار (5.1).
        if (SafeResolve.isRootMarker(name)) {
            return ArchiveEntryInfo(
                name = name,
                isDirectory = true,
                size = 0L,
                compressedSize = if (compressedSize < 0) -1L else compressedSize,
                modifiedAt = if (modifiedAt < 0) -1L else modifiedAt,
                isLink = isLink,
                unsafe = false
            )
        }
        val unsafe = try {
            SafeResolve.resolve(probeRoot, name)
            false
        } catch (e: UnsafeEntryException) {
            true
        }
        return ArchiveEntryInfo(
            name = name,
            isDirectory = isDirectory,
            size = if (size < 0) -1L else size,
            compressedSize = if (compressedSize < 0) -1L else compressedSize,
            modifiedAt = if (modifiedAt < 0) -1L else modifiedAt,
            isLink = isLink,
            unsafe = unsafe
        )
    }

    private fun plain(archive: Path): InputStream = Files.newInputStream(archive)

    private fun gz(archive: Path): InputStream =
        GzipCompressorInputStream(BufferedInputStream(Files.newInputStream(archive), 64 * 1024))

    private fun xz(archive: Path): InputStream =
        XZCompressorInputStream(
            BufferedInputStream(Files.newInputStream(archive), 64 * 1024),
            false,
            PREVIEW_XZ_MEMORY_LIMIT_KB
        )

    /** Counts bytes after decompression and stops TAR previews before scanning an unbounded payload. */
    private class PreviewLimitInputStream(
        source: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(source) {
        private var consumed = 0L
        private val skipBuffer = ByteArray(8 * 1024)

        private fun account(count: Int) {
            if (count <= 0) return
            if (count.toLong() > maxBytes - consumed) {
                throw ArchiveLimitException("archive preview scan exceeds $maxBytes bytes")
            }
            consumed += count
        }

        override fun read(): Int {
            val value = `in`.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val allowed = (maxBytes - consumed + 1L).coerceAtLeast(1L).coerceAtMost(length.toLong()).toInt()
            val count = `in`.read(buffer, offset, allowed)
            account(count)
            return count
        }

        override fun skip(count: Long): Long {
            if (count <= 0L) return 0L
            var skipped = 0L
            while (skipped < count) {
                val read = read(skipBuffer, 0, minOf(skipBuffer.size.toLong(), count - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }

    }

    // XZ dictionary use is constrained independently from the output-byte scan cap.
    private const val PREVIEW_XZ_MEMORY_LIMIT_KB = 256 * 1024
    private const val DEFAULT_MAX_STREAM_BYTES = 512L * 1024L * 1024L
}
