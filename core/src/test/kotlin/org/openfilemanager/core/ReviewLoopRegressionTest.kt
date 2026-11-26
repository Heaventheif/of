package org.openfilemanager.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

class ReviewLoopRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun dir(n: String): Path = tmp.newFolder(n).toPath()

    private fun names(root: Path): Set<String> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.toList().toSet() }

    // ---- دورة 6: مدخل STORED يعبر ZipArchiveOutputStream بنفس الطريقة والمحتوى ----
    @Test fun storedEntryRoundTripsThroughCommonsWriter() {
        val d = dir("s")
        val archive = d.resolve("a.zip")
        val payload = "stored-payload-1234567890".toByteArray()
        val expectedCrc = CRC32().apply { update(payload) }.value

        ZipOutputStream(Files.newOutputStream(archive)).use { z ->
            val e = ZipEntry("data.bin").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong(); compressedSize = payload.size.toLong(); crc = expectedCrc
            }
            z.putNextEntry(e); z.write(payload); z.closeEntry()
            // ومدخل DEFLATED عادي للمقارنة
            z.putNextEntry(ZipEntry("other.txt")); z.write("txt".toByteArray()); z.closeEntry()
        }

        val model = ZipModel.load(archive).model ?: throw AssertionError("not editable")
        assertEquals(listOf("data.bin", "other.txt"), model.entries().map { it.name })
        model.rename("other.txt", "renamed.txt")
        ZipRewriter.write(model)

        ZipFile(archive.toFile()).use { zf ->
            val stored = zf.getEntry("data.bin")!!
            assertEquals("STORED يبقى STORED", ZipEntry.STORED, stored.method)
            assertEquals(expectedCrc, stored.crc)
            assertEquals(payload.size.toLong(), stored.size)
            org.junit.Assert.assertArrayEquals("محتوى STORED سليم", payload, zf.getInputStream(stored).readBytes())
            assertEquals("txt", zf.getInputStream(zf.getEntry("renamed.txt")!!).readBytes().decodeToString())
        }
    }

    @Test fun keepBothNestedInsideKeepBothAlternativeResolvesDeepestOverride() {
        val src = dir("src2")
        val dst = dir("dst2")
        Files.createDirectories(src.resolve("outer/inner2"))
        Files.writeString(src.resolve("outer/inner2/deep.txt"), "deep")
        Files.writeString(src.resolve("outer/keep.txt"), "keep")
        // المجلد outer موجود في الوجهة كمجلد، لكن outer/inner2 موجود كملف: يعترض
        // مجلدًا متداخلًا داخل شجرة يتعارض جذرها جزئيًا فقط.
        Files.createDirectories(dst.resolve("outer"))
        Files.writeString(dst.resolve("outer/inner2"), "blocking file")

        val engine = FileOpEngine(MemoryOpJournal())
        runBlocking { engine.copy(listOf(src.resolve("outer")), dst, ConflictPolicy.KEEP_BOTH) }

        assertEquals("keep", Files.readString(dst.resolve("outer/keep.txt")))
        assertEquals("blocking file", Files.readString(dst.resolve("outer/inner2")))
        assertTrue("الشجرة البديلة كاملة بلا تكرار بادئة",
            names(dst).contains("outer/inner2 (1)/deep.txt"))
        assertEquals("deep", Files.readString(dst.resolve("outer/inner2 (1)/deep.txt")))
    }
}
