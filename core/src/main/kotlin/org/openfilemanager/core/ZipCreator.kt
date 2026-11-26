package org.openfilemanager.core

import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/** بيانات العناصر التي ستُضغط (تُحصى قبل البدء لشريط تقدم حقيقي). */
data class ZipPlanEntry(val file: Path, val entryName: String, val size: Long, val isDirectory: Boolean)

data class ZipCreateProgress(val entriesDone: Int, val entriesTotal: Int, val bytesDone: Long, val bytesTotal: Long)

class ZipCreateException(message: String) : IOException(message)

/** إنشاء ZIP: - أسماء UTF-8 مع علم اللغة EFS للأسماء العربية. */
class ZipCreator(maxPlanEntries: Int = DEFAULT_MAX_PLAN_ENTRIES) {

    private val maxPlanEntries = maxPlanEntries.coerceAtLeast(1)

    /** يحصي العناصر (بلا متابعة الروابط) ويحدد أسماءها داخل الأرشيف. */
    fun plan(sources: List<Path>, isCancelled: () -> Boolean = { false }): List<ZipPlanEntry> {
        val entries = ArrayList<ZipPlanEntry>()
        val usedNames = HashSet<String>() // أسماء مداخل ZIP حساسة لحالة الأحرف حسب المواصفة
        fun add(entry: ZipPlanEntry) {
            if (entries.size >= maxPlanEntries) {
                throw ZipCreateException("zip plan exceeded the entry limit: $maxPlanEntries")
            }
            val name = entry.entryName
            if (!usedNames.add(name)) {
                throw ZipCreateException("duplicate zip entry name: $name (${entry.file})")
            }
            val other = if (name.endsWith("/")) name.dropLast(1) else "$name/"
            if (usedNames.contains(other)) {
                throw ZipCreateException("zip entry file/folder clash: $name")
            }
            entries += entry
        }
        for (source in sources) {
            if (isCancelled()) throw OperationCancelledException()
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSymbolicLink(source)) continue
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                val rootName = source.fileName.toString()
                add(ZipPlanEntry(source, "$rootName/", 0, true))
                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isCancelled()) throw OperationCancelledException()
                        if (dir != source) {
                            val rel = source.relativize(dir).toString()
                            add(ZipPlanEntry(dir, "$rootName/$rel/", 0, true))
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isCancelled()) throw OperationCancelledException()
                        if (!attrs.isSymbolicLink) {
                            val rel = source.relativize(file).toString()
                            add(ZipPlanEntry(file, "$rootName/$rel", attrs.size(), false))
                        }
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                add(ZipPlanEntry(source, source.fileName.toString(), fileSize(file = source), false))
            }
        }
        return entries
    }

    /**
     * ينشئ الأرشيف ويعيد مساره النهائي. [destination] يجب ألا يوجد مسبقاً
     * (طبقة الواجهة تختار اسماً فريداً قبل الاستدعاء).
     */
    fun create(
        sources: List<Path>,
        destination: Path,
        onProgress: (ZipCreateProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Path {
        if (sources.isEmpty()) throw ZipCreateException(CoreOpCode.NO_ITEMS)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw ZipCreateException(CoreOpCode.message(CoreOpCode.DESTINATION_EXISTS, destination))

        // Plan before creating the destination directory: cancellation during a
        // potentially long tree walk must not leave an otherwise empty target path.
        val entries = plan(sources, isCancelled)
        if (entries.isEmpty()) throw ZipCreateException(CoreOpCode.NO_VALID_FILES)
        if (isCancelled()) throw OperationCancelledException()
        destination.parent?.let { Files.createDirectories(it) }
        val bytesTotal = entries.filterNot { it.isDirectory }.sumOf { it.size }
        val temporary = destination.resolveSibling(".fm-tmp-zip-${UUID.randomUUID()}")
        var bytesDone = 0L
        var entriesDone = 0

        try {
            Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { raw ->
                ZipArchiveOutputStream(raw).use { zip ->
                    zip.setEncoding("UTF-8")
                    zip.setUseLanguageEncodingFlag(true)
                    zip.setUseZip64(Zip64Mode.AsNeeded)
                    zip.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NOT_ENCODEABLE)

                    for (entry in entries) {
                        if (isCancelled()) throw OperationCancelledException()
                        val zipEntry = ZipArchiveEntry(entry.entryName)
                        zipEntry.setTime(lastModifiedMillis(entry.file))
                        if (entry.isDirectory) {
                            zipEntry.method = ZipEntry.STORED
                            zipEntry.size = 0
                            zipEntry.crc = 0
                            zip.putArchiveEntry(zipEntry)
                            zip.closeArchiveEntry()
                        } else if (isPreCompressed(entry.entryName)) {
                            writeStored(zip, entry, zipEntry, isCancelled) { read ->
                                bytesDone += read
                                onProgress(ZipCreateProgress(entriesDone, entries.size, bytesDone, bytesTotal))
                            }
                        } else {
                            writeDeflated(zip, entry, zipEntry, isCancelled) { read ->
                                bytesDone += read
                                onProgress(ZipCreateProgress(entriesDone, entries.size, bytesDone, bytesTotal))
                            }
                        }
                        entriesDone++
                        onProgress(ZipCreateProgress(entriesDone, entries.size, bytesDone, bytesTotal))
                    }
                    zip.finish()
                }
            }
            // A cancellation raised from the final progress callback is still
            // honoured before the archive's atomic publish point.
            if (isCancelled()) throw OperationCancelledException()
            AtomicIO.fsyncFile(temporary)
            AtomicIO.moveNoReplace(temporary, destination)
            return destination
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(temporary) }
            throw t
        }
    }

    /**
     * STORED needs a CRC before the ZIP local header. Read once to calculate it,
     * then verify the second pass while writing: source mutation between planning,
     * CRC calculation and output must not silently produce a corrupt archive.
     */
    private fun writeStored(
        zip: ZipArchiveOutputStream,
        entry: ZipPlanEntry,
        zipEntry: ZipArchiveEntry,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ) {
        val crc = CRC32()
        val buffer = ByteArray(BUFFER)
        var measured = 0L
        Files.newInputStream(entry.file).use { input ->
            while (true) {
                if (isCancelled()) throw OperationCancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                measured += n
                crc.update(buffer, 0, n)
            }
        }
        if (measured != entry.size) throw ZipCreateException("source size changed while planning: ${entry.file}")
        val expectedCrc = crc.value
        zipEntry.method = ZipEntry.STORED
        zipEntry.size = measured
        zipEntry.crc = expectedCrc
        zip.putArchiveEntry(zipEntry)
        var written = 0L
        val secondCrc = CRC32()
        Files.newInputStream(entry.file).use { input ->
            while (true) {
                if (isCancelled()) throw OperationCancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                secondCrc.update(buffer, 0, n)
                zip.write(buffer, 0, n)
                onBytes(n.toLong())
            }
        }
        if (written != measured || secondCrc.value != expectedCrc) {
            throw ZipCreateException("source changed while archiving: ${entry.file}")
        }
        zip.closeArchiveEntry()
    }

    private fun writeDeflated(
        zip: ZipArchiveOutputStream,
        entry: ZipPlanEntry,
        zipEntry: ZipArchiveEntry,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ) {
        zipEntry.method = ZipEntry.DEFLATED
        zip.putArchiveEntry(zipEntry)
        val buffer = ByteArray(BUFFER)
        Files.newInputStream(entry.file).use { input ->
            while (true) {
                if (isCancelled()) throw OperationCancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                zip.write(buffer, 0, n)
                onBytes(n.toLong())
            }
        }
        zip.closeArchiveEntry()
    }

    private fun lastModifiedMillis(file: Path): Long =
        tryOrNull { Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() } ?: 0L

    private fun fileSize(file: Path): Long =
        tryOrNull { Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size() } ?: 0L

    companion object {
        private const val BUFFER = 64 * 1024

        const val DEFAULT_MAX_PLAN_ENTRIES = 100_000

        /** امتدادات تُخزَّن بلا ضغط إضافي: مضغوطة أصلاً فالضغط هدر وقت بلا ربح. */
        private val PRE_COMPRESSED = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp",
            "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav",
            "mp4", "mkv", "webm", "3gp",
            "zip", "apk", "apks", "jar", "kmz", "ipa",
            "gz", "xz", "zst", "bz2", "lz4", "7z", "rar", "tgz", "txz"
        )

        fun isPreCompressed(entryName: String): Boolean {
            val name = entryName.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot < 0) return false
            return name.substring(dot + 1).lowercase() in PRE_COMPRESSED
        }
    }
}
