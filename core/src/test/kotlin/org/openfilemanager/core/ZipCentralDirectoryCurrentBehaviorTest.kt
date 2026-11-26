package org.openfilemanager.core

import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipCentralDirectoryCurrentBehaviorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun zipWithEntries(path: Path, count: Int, prefix: String = "e") {
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            for (i in 1..count) {
                z.putNextEntry(ZipEntry("$prefix$i.txt"))
                z.write("content-$i".toByteArray())
                z.closeEntry()
            }
        }
    }

    // ---- أرشيف عادي تحت السقف: نتائج كما هي بالضبط ----
    @Test
    fun normalUnderCapZipListsExactlyAndLoadsEditable() {
        val p = dir("normal").resolve("a.zip")
        zipWithEntries(p, 100)

        val listing = ArchiveInspector.list(p)
        assertEquals(100, listing.entries.size)
        assertFalse(listing.truncated)
        assertEquals(100, listing.scanned)
        assertEquals("e1.txt", listing.entries.first().name)
        assertEquals("e100.txt", listing.entries.last().name)
        assertEquals("content-42".length.toLong(), listing.entries[41].size)
        assertFalse(listing.entries.any { it.unsafe })

        val load = ZipModel.load(p)
        assertNotNull(load.model)
        assertNull(load.reason)
        assertEquals(100, load.model!!.entries().size)
    }

    // ---- أرشيف أصغر بواحد من السقف: بدون اقتطاع كما اليوم ----
    @Test
    fun justUnderCapZipIsNotTruncated() {
        val p = dir("just-under").resolve("b.zip")
        zipWithEntries(p, 4_999)

        val listing = ArchiveInspector.list(p)
        assertEquals(4_999, listing.scanned)
        assertEquals(4_999, listing.entries.size)
        assertFalse(listing.truncated)
    }

    // ---- أرشيف عند السقف تماماً: بدون اقتطاع كما اليوم ----
    @Test
    fun exactlyAtCapZipIsNotTruncated() {
        val p = dir("at-cap").resolve("c.zip")
        zipWithEntries(p, 5_000)

        val listing = ArchiveInspector.list(p, maxEntries = 5_000)
        assertEquals(5_000, listing.scanned)
        assertFalse(listing.truncated)
        assertEquals(5_000, ZipModel.load(p).model!!.entries().size)
    }

    // ---- ZIP64 بوضع Always: يُفتح ويُقرأ كما اليوم ----
    @Test
    fun zip64UnderCapOpensAsToday() {
        val p = dir("zip64").resolve("d.zip")
        ZipArchiveOutputStream(Files.newOutputStream(p)).use { z ->
            z.setUseZip64(Zip64Mode.Always)
            for (i in 1..50) {
                z.putArchiveEntry(ZipArchiveEntry("z$i.txt"))
                z.write("x".toByteArray())
                z.closeArchiveEntry()
            }
        }
        val listing = ArchiveInspector.list(p)
        assertEquals(50, listing.scanned)
        assertTrue(ZipModel.load(p).editable)
    }

    // ---- تعليق لاحق (EOCD comment): يُفتح كما اليوم ----
    @Test
    fun zipWithTrailingCommentOpensAsToday() {
        val p = dir("comment").resolve("e.zip")
        ZipArchiveOutputStream(Files.newOutputStream(p)).use { z ->
            z.setComment("made by a tool with a trailing comment")
            for (i in 1..10) {
                z.putArchiveEntry(ZipArchiveEntry("k$i.txt"))
                z.write("v$i".toByteArray())
                z.closeArchiveEntry()
            }
        }
        val listing = ArchiveInspector.list(p)
        assertEquals(10, listing.scanned)
        assertTrue(ZipModel.load(p).editable)
    }

    // ---- العدد المعلن في EOCD أقل من الحقيقي: يُقرأ كامل CD الحقيقي كما اليوم ----
    @Test
    fun lyingSmallDeclaredCountStillListsAllRealEntries() {
        val p = dir("lying").resolve("f.zip")
        zipWithEntries(p, 200)
        // نرقّع العدد المعلن في EOCD من 200 إلى 3 دون تغيير cdSize/cdOffset.
        val bytes = Files.readAllBytes(p)
        val eocdPos = bytes.size - 22
        assertEquals(0x06054b50, readLe32(bytes, eocdPos))
        writeLe16(bytes, eocdPos + 10, 3) // total entries
        Files.write(p, bytes)

        val listing = ArchiveInspector.list(p)
        assertEquals("القراءة الفعلية للـ CD لا تتأثر بالعدد المعلن الكاذب", 200, listing.scanned)
        assertTrue(ZipModel.load(p).editable)
    }

    // ---- EOCD ناقص (اقتطاع): نفس سلوك اليوم — ZipFile يرفض برميه الخاص ----
    @Test
    fun truncatedEocdFallsBackToLegacyZipFileError() {
        val p = dir("truncated").resolve("g.zip")
        zipWithEntries(p, 20)
        val bytes = Files.readAllBytes(p)
        Files.write(p, bytes.copyOfRange(0, bytes.size - 10))

        val before = try {
            ArchiveInspector.list(p)
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("اليوم يفشل فتح المقصوص — يجب أن يبقى فاشلاً", before)
        assertFalse("الفشل يجب أن يأتي من ZipFile لا من الفحص الجديد",
            before is ArchiveLimitException)

        // المتصل الفعلي (BrowserViewModel) يلفّ load بـ runCatching — نقاطه هنا:
        val load = runCatching { ZipModel.load(p) }.getOrNull()
        assertFalse("لا تحرير لأرشيف مقصوص", load?.editable == true)
    }

    // ---- ملف عشوائي (لا EOCD): نفس سلوك اليوم ----
    @Test
    fun garbageFileFallsBackToLegacyZipFileError() {
        val p = dir("garbage").resolve("h.zip")
        Files.write(p, ByteArray(4_096) { (it % 251).toByte() })

        val before = try {
            ArchiveInspector.list(p)
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(before)
        assertFalse(before is ArchiveLimitException)
        val load = runCatching { ZipModel.load(p) }.getOrNull()
        assertFalse(load?.editable == true)
    }

    // ---- قراءة حقول EOCD (أدوات الاختبار) ----
    internal fun readLe32(b: ByteArray, pos: Int): Long =
        (b[pos].toLong() and 0xFF) or ((b[pos + 1].toLong() and 0xFF) shl 8) or
            ((b[pos + 2].toLong() and 0xFF) shl 16) or ((b[pos + 3].toLong() and 0xFF) shl 24)

    internal fun writeLe16(b: ByteArray, pos: Int, v: Int) {
        b[pos] = (v and 0xFF).toByte(); b[pos + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
