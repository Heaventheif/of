package org.openfilemanager.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** إصلاحات جولة المراجعة الثانية: حساسية الحالة تُستشرف من الوجهة، وتكرار الاسم الدقيق يبقى مرفوضاً. */
class DeepReviewFixesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarWith(vararg entries: Pair<String, String>): File {
        val f = File(tmp.root, "case.tar")
        TarArchiveOutputStream(FileOutputStream(f)).use { t ->
            entries.forEach { (name, content) ->
                val e = TarArchiveEntry(name)
                e.size = content.toByteArray().size.toLong()
                t.putArchiveEntry(e)
                t.write(content.toByteArray())
                t.closeArchiveEntry()
            }
            t.finish()
        }
        return f
    }

    @Test
    fun `case-sensitive destinations keep case-colliding entries`() {
        // نظام الملفات في بيئة الاختبار ext4: المفتش يعيد true فيستقبل README/readme معاً.
        val dest = tmp.newFolder("out")
        val report = ArchiveExtractor().extract(tarWith("README" to "upper", "readme" to "lower").toPath(), dest.toPath())
        assertEquals(2, report.written)
        assertEquals("upper", dest.resolve("case/README").readText())
        assertEquals("lower", dest.resolve("case/readme").readText())
    }

    @Test
    fun `exact duplicate names are still rejected`() {
        val dest = tmp.newFolder("out2")
        assertThrows(UnsafeEntryException::class.java) {
            ArchiveExtractor().extract(tarWith("README" to "a", "README" to "b").toPath(), dest.toPath())
        }
        // الفشل ينظف المجلد المؤقت: لا بقايا .fm-extract-*
        assertTrue(dest.listFiles()!!.isEmpty())
    }

    @Test
    fun `extraction leaves no probe files behind`() {
        val dest = tmp.newFolder("out3")
        ArchiveExtractor().extract(tarWith("a.txt" to "x").toPath(), dest.toPath())
        val leftovers = dest.walkTopDown().filter { it.name.startsWith(".fm-case-probe") || it.name.startsWith(".fm-extract") }.toList()
        assertTrue("leftovers: $leftovers", leftovers.isEmpty())
        assertTrue(dest.resolve("case/a.txt").exists())
    }

    @Test
    fun `relaxed limits are only reachable via explicit factory`() {
        val relaxed = ExtractLimits.relaxed(maxTotalBytes = 1024)
        assertEquals(2000L, relaxed.maxRatio)
        assertEquals(2000L, relaxed.maxTotalRatio)
        assertEquals(64L * 1024 * 1024, relaxed.ratioSlackBytes)
        assertEquals(1024L, relaxed.maxTotalBytes)
        // الافتراضي الصارم لم يتغير
        assertEquals(200L, ExtractLimits().maxRatio)
    }

    @Test
    fun `deeply nested case probe does not leak into final tree`() {
        // أرشيف بمجلدات متداخلة يمر فوق المفتش ثم يُنقل كاملاً
        val dest = tmp.newFolder("out4")
        val report = ArchiveExtractor().extract(
            tarWith("d1/d2/README.md" to "content", "d1/other.txt" to "y").toPath(), dest.toPath()
        )
        assertEquals(2, report.written)
        assertTrue(Files.exists(dest.toPath().resolve("case/d1/d2/README.md")))
    }
}
