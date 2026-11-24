package org.openfilemanager.core

import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/**
 * Disk side of ZIP editing. [write] never touches the original until a complete, verified copy exists (and an in-place fallback keeps a backup):
 * build in a temp file next to it, re-open and check, then replace atomically. On any failure the
 * original is untouched and the temp file is removed.
 */
object ZipRewriter {
    private const val SPACE_MARGIN = 32L * 1024 * 1024

    /** (حدود extractOne) سقف مطلق لناتج مدخل واحد يُفتح للعرض (512 م.ب). */
    private const val EXTRACT_ONE_MAX_BYTES = 512L shl 20

    /** هوامش مُعامل الفحش: سماحية ثابتة + نسبة 200:1 كحد أعلى للمدخل المضغوط. */
    private const val EXTRACT_ONE_SLACK_BYTES = 1L shl 20
    private const val EXTRACT_ONE_RATIO = 200L

    /** Saves [model] over its archive. Throws IOException (original untouched) on any problem. */
    fun write(model: ZipModel, isCancelled: () -> Boolean = { false }) {
        val archive = model.archive
        val entries = model.entries()
        val extra = entries.sumOf { (it.source as? ZipSource.External)?.let { s -> runCatching { Files.size(s.path) }.getOrDefault(0L) } ?: 0L }
        // File.usableSpace (statvfs) is reliable on Android; Files.getFileStore() can throw or report 0 on shared storage.
        // 0 means "unknown", so only a real, positive figure that is too small blocks the save.
        val usable = runCatching { archive.toFile().usableSpace }.getOrDefault(0L)
        if (usable > 0 && usable < Files.size(archive) + extra + SPACE_MARGIN) throw IOException("not enough free space to save")

        val temp = archive.resolveSibling(".${archive.fileName}.edit-${System.nanoTime()}.tmp")
        try {
            // (F20) Commons ZipFile يكشف الخصائص الخارجية التي يخفيها java.util.zip.
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(archive).get().use { source ->
                // (F20) ZipArchiveOutputStream (Commons Compress) بدل java.util.zip:
                // الوحيد الذي يكتب الخصائص الخارجية (وضع Unix) على المداخل.
                ZipArchiveOutputStream(BufferedOutputStream(Files.newOutputStream(temp))).use { rawOut ->
                    // أسماء UTF-8 مع علم EFS (نفس سلوك ZipCreator للأسماء العربية).
                    rawOut.setEncoding(Charsets.UTF_8.name())
                    val out = ZipOut(rawOut)
                    for (info in entries) {
                        if (isCancelled()) throw IOException("cancelled")
                        when (val src = info.source) {
                            is ZipSource.Directory -> {
                                val dir = ZipEntry(info.name)
                                if (info.modifiedAt > 0) dir.time = info.modifiedAt
                                out.put(dir, info.unixMode, isDirectory = true)
                                out.close()
                            }
                            is ZipSource.Original -> {
                                val old = source.getEntry(src.name) ?: throw IOException("entry vanished: ${src.name}")
                                val e = ZipEntry(info.name)
                                if (old.time >= 0) e.time = old.time
                                old.comment?.let { e.comment = it }
                                if (old.method == ZipEntry.STORED) {
                                    e.method = ZipEntry.STORED
                                    e.size = old.size; e.compressedSize = old.size; e.crc = old.crc
                                }
                                // (F20) استعادة وضع Unix الأصلي (من الخصائص الخارجية للمدخل القديم نفسه).
                                out.put(e, src.unixMode, isDirectory = false)
                                source.getInputStream(old).use { it.copyTo(out.stream()) } // throws for encrypted entries -> abort, original safe
                                out.close()
                            }
                            is ZipSource.External -> {
                                val e = ZipEntry(info.name)
                                if (info.modifiedAt > 0) e.time = info.modifiedAt
                                out.put(e, info.unixMode, isDirectory = false)
                                Files.newInputStream(src.path).use { it.copyTo(out.stream()) }
                                out.close()
                            }
                        }
                    }
                }
            }
            verify(temp, entries)
            replaceOriginal(temp, archive, entries)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    /**
     * (F20) غلاف موحّد فوق ZipArchiveOutputStream: يبدّل java.util.zip.ZipEntry
     * إلى ZipArchiveEntry (يمرر الاسم/الوقت/التعليق/STORED كما هي) ويكتب وضع
     * Unix في الخصائص الخارجية بتنسيق info-zip: بتات النظام صفر، 3.0 (Unix)
     * في الحقل العلوي، والوضع في الحقلين الأوسطين. -1 = لا شيء مسجل فلا يُمَس.
     */
    private class ZipOut(private val raw: ZipArchiveOutputStream) {
        fun put(e: ZipEntry, unixMode: Int, isDirectory: Boolean) {
            val out = org.apache.commons.compress.archivers.zip.ZipArchiveEntry(e.name).apply {
                time = e.time
                e.comment?.let { comment = it }
                if (e.method == ZipEntry.STORED) {
                    method = ZipEntry.STORED
                    size = e.size; compressedSize = e.compressedSize; crc = e.crc
                }
            }
            if (unixMode > 0) {
                val type = if (isDirectory) 0x4000 else 0x8000
                out.externalAttributes = (((type or (unixMode and 0xFFF)) shl 16) or (3 shl 0)).toLong()
            }
            raw.putArchiveEntry(out)
        }

        fun stream() = raw
        fun close() = raw.closeArchiveEntry()
    }

    /**
     * Atomic rename first. Android shared storage (FUSE) often refuses ATOMIC_MOVE or a rename over a file
     * the app did not create, so fall back to a plain move and finally to an in-place overwrite that is
     * always guarded by a full backup copy of the original (see [overwriteWithBackup]). The temp copy is
     * fsynced first and is already verified; it stays on disk until this returns.
     */
    private fun replaceOriginal(temp: Path, archive: Path, entries: List<ZipEntryInfo>) {
        AtomicIO.fsyncFile(temp)
        try {
            Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            AtomicIO.fsyncDirectory(archive.parent)
            return
        } catch (_: AtomicMoveNotSupportedException) {
        } catch (_: IOException) {
        }
        try {
            Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING)
            AtomicIO.fsyncDirectory(archive.parent)
            return
        } catch (_: IOException) {
        }
        overwriteWithBackup(temp, archive, entries)
    }

    /**
     * Last resort: the original is never truncated before a complete backup copy exists. If writing or
     * verifying the new content fails, the backup is copied back. If even that fails the backup is kept
     * under a visible name (".bak") that the startup cleaner does not touch, and its path is reported.
     */
    internal fun overwriteWithBackup(temp: Path, archive: Path, entries: List<ZipEntryInfo>) {
        val backup = uniqueBackup(archive)
        try {
            Files.copy(archive, backup)
            AtomicIO.fsyncFile(backup)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(backup) }
            throw IOException("could not back up the original; it was left untouched", t)
        }
        try {
            copyOver(temp, archive)
            verify(archive, entries)
        } catch (t: Throwable) {
            try {
                copyOver(backup, archive)
            } catch (restore: Throwable) {
                t.addSuppressed(restore)
                throw IOException("save failed and the original could not be restored; backup kept at $backup", t)
            }
            runCatching { Files.deleteIfExists(backup) }
            throw t
        }
        runCatching { Files.deleteIfExists(backup) }
    }

    private fun copyOver(from: Path, to: Path) {
        FileChannel.open(to, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            Files.newInputStream(from).use { it.copyTo(Channels.newOutputStream(ch)) }
            ch.force(true)
        }
    }

    private fun uniqueBackup(archive: Path): Path {
        var n = 0
        while (true) {
            val candidate = archive.resolveSibling("${archive.fileName}.${if (n == 0) "" else "$n."}bak")
            if (!Files.exists(candidate)) return candidate
            n++
        }
    }

    private fun verify(temp: Path, expected: List<ZipEntryInfo>) {
        java.util.zip.ZipFile(temp.toFile()).use { zf ->
            val names = HashSet<String>()
            val e = zf.entries()
            val buf = ByteArray(64 * 1024)
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                names += entry.name
                if (entry.isDirectory) continue
                // Fully consume each entry: validates payload readability, size and CRC.
                val crc = java.util.zip.CRC32()
                var total = 0L
                zf.getInputStream(entry).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (entry.size >= 0 && total > entry.size) throw IOException("verification failed")
                        crc.update(buf, 0, n)
                    }
                }
                if (entry.size >= 0 && total != entry.size) throw IOException("verification failed")
                if (entry.crc != -1L && crc.value != entry.crc) throw IOException("verification failed")
            }
            if (names.size != expected.size || expected.any { it.name !in names }) throw IOException("verification failed")
        }
    }

    /**
     * Copies one stored entry (by its ORIGINAL name) to [dest]; used to open an entry without extracting the rest.
     *
     * (حدود) كل مسارات الاستخراج الأخرى تعمل تحت [ExtractLimits] — هذا المسار كان
     * الاستثناء: Files.copy بلا أي حد، فمدخل مضغوط بمُعامل ~1000:1 أو بحجم
     * معلن كاذب يملأ تقسيم /data كله (حجب التطبيقات) قبل أول فحص. الآن:
     * سقف مطلق لناتج المدخل، سقف مُعامل مقارنةً بالحجم المضغوط المعلن، تحقق من
     * الحجم المعلن بعد النسخ، ونشر مؤقت ذري — لا ملف نصف مكتوب باسم المدخل.
     */
    fun extractOne(archive: Path, originalName: String, dest: Path) {
        java.util.zip.ZipFile(archive.toFile()).use { zf ->
            val entry = zf.getEntry(originalName)?.takeIf { !it.isDirectory } ?: throw IOException("entry not found")
            if (entry.size in 1 until Long.MAX_VALUE && entry.size > EXTRACT_ONE_MAX_BYTES) {
                throw IOException("entry too large to open (${entry.size} bytes)")
            }
            Files.createDirectories(dest.parent)
            val tmp = dest.resolveSibling(".fm-view-${System.nanoTime()}")
            try {
                var written = 0L
                zf.getInputStream(entry).use { ins ->
                    Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > EXTRACT_ONE_MAX_BYTES) {
                                throw IOException("entry expands beyond ${EXTRACT_ONE_MAX_BYTES} bytes (zip bomb?)")
                            }
                            // مُعامل الفحش: مدخل مضغوط معلن مع ناتج أضخم بكثير منه.
                            val compressed = entry.compressedSize
                            if (compressed in 1 until Long.MAX_VALUE) {
                                val allowed = compressed + EXTRACT_ONE_SLACK_BYTES + EXTRACT_ONE_RATIO * (compressed + 64 * 1024)
                                if (written > allowed) throw IOException("entry expansion ratio beyond limit")
                            }
                            os.write(buf, 0, n)
                        }
                    }
                }
                // الحجم المعلن (إن وُجد) عقد لا تهاون: مدخل كاذب يُرفض كاملاً.
                if (entry.size in 1 until Long.MAX_VALUE && written != entry.size) {
                    throw IOException("entry size mismatch (declared ${entry.size}, got $written)")
                }
                if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                    AtomicIO.moveAtomicOrFallback(tmp, dest, replace = true)
                } else {
                    AtomicIO.moveNoReplace(tmp, dest)
                }
            } catch (t: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                throw t
            }
        }
    }

    /**
     * Extracts the selected entries (folders with everything under them) straight into [destDir],
     * keeping their own names and never overwriting: clashes get " (n)". Returns the files written.
     */
    fun extract(archive: Path, names: Collection<String>, destDir: Path, isCancelled: () -> Boolean = { false }): List<Path> {
        Files.createDirectories(destDir)
        val root = destDir.toAbsolutePath().normalize()
        val top = names.filter { n -> names.none { o -> o != n && o.endsWith("/") && n.startsWith(o) } }
        val written = ArrayList<Path>()
        java.util.zip.ZipFile(archive.toFile()).use { zf ->
            val all = ArrayList<ZipEntry>()
            val en = zf.entries()
            while (en.hasMoreElements()) all += en.nextElement()
            for (sel in top) {
                val isDir = sel.endsWith("/")
                val parent = sel.trimEnd('/').let { if (it.contains('/')) it.substringBeforeLast('/') + "/" else "" }
                val matching = all.filter { if (isDir) it.name.startsWith(sel) else it.name == sel }
                if (matching.isEmpty()) continue
                val topBase = uniqueOnDisk(root, sel.removePrefix(parent).trimEnd('/'))
                val topPath = root.resolve(topBase).normalize()
                if (!topPath.startsWith(root)) throw IOException("unsafe path")
                for (e in matching) {
                    if (isCancelled()) throw IOException("cancelled")
                    val rel = e.name.removePrefix(sel)
                    val target = (if (rel.isEmpty()) topPath else topPath.resolve(rel)).normalize()
                    if (!target.startsWith(root)) throw IOException("unsafe path")
                    if (e.isDirectory) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        // (حدود) نسخ مقيد كسائر الاستخراج الآخر: مدخل مضغوط بمُعامل
                        // تضخم هائل (قنبلة مضغوطة) لا يملأ القرص — سقف مطلق للناتج،
                        // سقف مُعامل مقابل الحجم المضغوط المعلن، وتحقق من الحجم المعلن.
                        var entryBytes = 0L
                        zf.getInputStream(e).use { ins ->
                            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { os ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    entryBytes += n
                                    if (entryBytes > EXTRACT_ONE_MAX_BYTES) {
                                        throw IOException("entry expands beyond $EXTRACT_ONE_MAX_BYTES bytes (zip bomb?)")
                                    }
                                    val compressed = e.compressedSize
                                    if (compressed in 1 until Long.MAX_VALUE) {
                                        val allowed = compressed + EXTRACT_ONE_SLACK_BYTES + EXTRACT_ONE_RATIO * (compressed + 64 * 1024)
                                        if (entryBytes > allowed) throw IOException("entry expansion ratio beyond limit")
                                    }
                                    os.write(buf, 0, n)
                                }
                            }
                        }
                        if (e.size in 1 until Long.MAX_VALUE && entryBytes != e.size) {
                            throw IOException("entry size mismatch (declared ${e.size}, got $entryBytes)")
                        }
                        written.add(target)
                    }
                }
                if (isDir) Files.createDirectories(topPath)
            }
        }
        return written
    }

    private fun uniqueOnDisk(root: Path, base: String): String {
        if (!Files.exists(root.resolve(base))) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var n = 1
        while (Files.exists(root.resolve("$stem ($n)$ext"))) n++
        return "$stem ($n)$ext"
    }
}
