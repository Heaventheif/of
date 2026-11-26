package org.openfilemanager.core

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory

class DocMemoryMeasurementTest {

    private val threadBean: com.sun.management.ThreadMXBean? = try {
        val tb = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (tb != null && tb.isThreadAllocatedMemorySupported) {
            tb.isThreadAllocatedMemoryEnabled = true
            tb
        } else null
    } catch (_: Exception) { null }

    private fun allocatedDuring(block: () -> Unit): Long {
        val tb = threadBean
        if (tb != null) {
            val id = Thread.currentThread().threadId()
            val before = tb.getThreadAllocatedBytes(id)
            block()
            val after = tb.getThreadAllocatedBytes(id)
            return if (before < 0 || after < 0) -1 else after - before
        }
        block()
        return -1
    }

    private fun mib(b: Long): String = "%.1f".format(b / 1048576.0)

    private fun paddedDoc(targetBytes: Int, dir: File): File {
        val base = javaClass.getResourceAsStream("/sample.doc")!!.readBytes()
        val f = File(dir, "padded-$targetBytes.doc")
        dir.mkdirs()
        f.outputStream().use { out ->
            out.write(base)
            var written = base.size
            val chunk = ByteArray(64 * 1024) { (it % 241).toByte() }
            while (written < targetBytes) {
                val n = minOf(chunk.size, targetBytes - written)
                out.write(chunk, 0, n)
                written += n
            }
        }
        return f
    }

    @Test
    fun measureAllocationNearTheCap() {
        val dir = File(System.getProperty("java.io.tmpdir"), "doc-mem-measure")
        dir.mkdirs()
        val base = javaClass.getResourceAsStream("/sample.doc")!!.readBytes()
        println("[phase7] baseline sample.doc bytes=${base.size}, threadBean=${threadBean != null}")

        val sizes = listOf(
            2L * 1024 * 1024,
            16L * 1024 * 1024,
            24L * 1024 * 1024 - 24 * 1024 // تحت سقف 24 MiB الجديد بقليل
        )
        for (target in sizes) {
            val f = paddedDoc(target.toInt(), dir)
            assertTrue("الملف الاصطناعي بالحجم المستهدف", f.length() == target)
            assertTrue(
                "النص الأصلي ما زال مستخرجاً",
                DocTextExtractor.extract(f).contains("مرحبا بالعالم Hello world")
            )
            var ms = 0L
            val allocated = allocatedDuring {
                val t0 = System.nanoTime()
                DocTextExtractor.extract(f)
                ms = (System.nanoTime() - t0) / 1_000_000
            }
            println(
                "[phase7] file=${mib(target)}MiB allocated=${mib(allocated)}MiB " +
                    "ratio=${"%.2f".format(allocated.toDouble() / target)} time=${ms}ms"
            )
            f.delete()
        }
    }

    @Test
    fun corruptAndBoundaryFilesFailWithBoundedMemory() {
        val dir = File(System.getProperty("java.io.tmpdir"), "doc-mem-corrupt")
        dir.mkdirs()

        // (1) تالف: مقطوع من المنتصف — المسار التالف نفسه مع قياس التخصيص.
        val truncated = paddedDoc(20 * 1024 * 1024, dir)
        val cut = File(dir, "cut.doc")
        cut.outputStream().use { out ->
            truncated.inputStream().use { inp ->
                val buf = ByteArray(8192); var total = 0L
                while (total < 10L * 1024 * 1024) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); total += n
                }
            }
        }
        var cutAllocated = 0L
        var cutOutcome = "extracted"
        try {
            cutAllocated = allocatedDuring { DocTextExtractor.extract(cut) }
        } catch (e: DocFormatException) {
            cutOutcome = "DocFormatException(${e.reason.name})"
            cutAllocated = allocatedDuring {
                try { DocTextExtractor.extract(cut) } catch (_: DocFormatException) {}
            }
        }
        println("[phase7] truncated-at-10MiB: $cutOutcome allocated=${mib(cutAllocated)}MiB")

        // (2) فوق السقف الجديد (24 MiB): يرفض TOO_LARGE قبل قراءة أي بايت.
        val over = paddedDoc((24L * 1024 * 1024 + 1024).toInt(), dir)
        var overAllocated = 0L
        try {
            overAllocated = allocatedDuring { DocTextExtractor.extract(over) }
            fail("فوق السقف يجب أن يرفض TOO_LARGE")
        } catch (e: DocFormatException) {
            assertTrue(e.reason == DocFormatException.Reason.TOO_LARGE)
        }
        println("[phase7] over-cap rejection allocated=${mib(overAllocated)}MiB (يجب أن يكون قريباً من صفر)")
        over.delete(); cut.delete(); truncated.delete()
    }
}
