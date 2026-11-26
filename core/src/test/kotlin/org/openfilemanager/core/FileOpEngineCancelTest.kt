package org.openfilemanager.core
import org.openfilemanager.core.CoreOpCode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FileOpEngineCancelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    private fun noTempLeftovers(dst: Path): Boolean =
        Files.list(dst).use { s -> s.noneMatch { it.fileName.toString().startsWith(".fm-tmp-") } }

    @Test
    fun cancelDuringSecondFileKeepsFirstAndLeavesNoTemps() = runBlocking {
        val src = dir("c1-src")
        val dst = dir("c1-dst")
        write(src.resolve("a.txt"), "AAA")
        write(src.resolve("b.txt"), "BBB")
        var cancelled = false
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(
            journal,
            onProgress = { p -> if (p.itemIndex >= 1) cancelled = true },
            isCancelled = { cancelled }
        )

        try {
            engine.copy(listOf(src.resolve("a.txt"), src.resolve("b.txt")), dst, ConflictPolicy.SKIP)
            fail("الإلغاء أثناء العنصر الثاني يجب أن يرمي OperationCancelledException")
        } catch (_: OperationCancelledException) {
        }

        // العنصر الأول أُتمّ وانشر قبل وصول الإلغاء.
        assertEquals("AAA", Files.readString(dst.resolve("a.txt")))
        assertFalse("العنصر الثاني لم يُنشر (لا ملف جزئي باسم نهائي)", Files.exists(dst.resolve("b.txt")))
        assertTrue("لا ملفات مؤقتة متروكة في مجلد الوجهة", noTempLeftovers(dst))
        assertTrue(Files.exists(src.resolve("b.txt")))

        // السجل: العملية CANCELLED بعدّادات مصفّرة رغم إنجاز العنصر الأول.
        val record = journal.op(journal.ops().single().id)!!
        assertEquals(OpState.CANCELLED, record.state)
        assertEquals(0, record.ok)
        assertEquals(0, record.failed)
        assertEquals(0, record.skipped)
        val items = journal.items(record.id)
        assertEquals(ItemState.DONE, items.first { it.src.endsWith("a.txt") }.state)
        assertEquals(ItemState.RUNNING, items.first { it.src.endsWith("b.txt") }.state)
    }

    @Test
    fun cancelDuringFirstFilePublishesNothing() = runBlocking {
        val src = dir("c2-src")
        val dst = dir("c2-dst")
        write(src.resolve("a.txt"), "AAA")
        var cancelled = false
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(
            journal,
            onProgress = { _ -> cancelled = true },
            isCancelled = { cancelled }
        )

        try {
            engine.copy(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP)
            fail()
        } catch (_: OperationCancelledException) {
        }

        assertEquals("لا نشر لأول ملف عند الإلغاء وسط نسخه", 0, Files.list(dst).use { it.count() })
        val items = journal.items(journal.ops().single().id)
        assertEquals(ItemState.RUNNING, items.single().state)
        assertEquals(OpState.CANCELLED, journal.op(journal.ops().single().id)!!.state)
    }

    @Test
    fun cancelBeforeStartStillCreatesTargetAndRecordsCancelled() = runBlocking {
        val root = dir("c3")
        val src = dir("c3-src")
        write(src.resolve("a.txt"), "AAA")
        val dst = root.resolve("not-created-yet") // غير موجود قبل العملية
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(journal, isCancelled = { true })

        try {
            engine.copy(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP)
            fail()
        } catch (_: OperationCancelledException) {
        }

        // سلوك حالي: مجلد الوجهة يُنشأ قبل أول فحص إلغاء (سلوك صارم لاحقاً؟).
        assertTrue("مجلد الوجهة موجود رغم الإلغاء قبل البدء", Files.exists(dst))
        val record = journal.op(journal.ops().single().id)!!
        assertEquals(OpState.CANCELLED, record.state)
        assertEquals(0, record.ok)
        assertEquals(ItemState.PENDING, journal.items(record.id).single().state)
        assertEquals(0, Files.list(dst).use { it.count() })
    }

    @Test
    fun cancelBeforeDeleteRemovesNothing() = runBlocking {
        val d = dir("c4")
        write(d.resolve("tree/a.txt"), "A")
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(journal, isCancelled = { true })

        try {
            engine.delete(listOf(d.resolve("tree")))
            fail()
        } catch (_: OperationCancelledException) {
        }

        assertTrue("الشجرة لم تُمس", Files.exists(d.resolve("tree/a.txt")))
        val record = journal.op(journal.ops().single().id)!!
        assertEquals(OpState.CANCELLED, record.state)
        assertEquals(ItemState.PENDING, journal.items(record.id).single().state)
    }

    @Test
    fun cancelAtDeleteStartLeavesTreeIntact() = runBlocking {
        val d = dir("c5")
        write(d.resolve("tree/a.txt"), "A")
        var cancelled = false
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(
            journal,
            onProgress = { _ -> cancelled = true }, // تقدم الحذف يسبق أول حذف فعلي
            isCancelled = { cancelled }
        )

        try {
            engine.delete(listOf(d.resolve("tree")))
            fail()
        } catch (_: OperationCancelledException) {
        }

        // فحص الإلغاء عند أول مدخل للشجرة (preVisitDirectory) يسبق أي حذف.
        assertTrue(Files.exists(d.resolve("tree/a.txt")))
        val record = journal.op(journal.ops().single().id)!!
        assertEquals(OpState.CANCELLED, record.state)
        assertEquals(ItemState.RUNNING, journal.items(record.id).single().state)
    }

    @Test
    fun cancelledCopyCannotBeResumed() = runBlocking {
        val src = dir("c6-src")
        val dst = dir("c6-dst")
        write(src.resolve("a.txt"), "AAA")
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(journal, isCancelled = { true })
        val opId = FileOpEngine.newOpId()

        try {
            runBlocking { engine.copy(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP, opId) }
            fail()
        } catch (_: OperationCancelledException) {
        }
        assertEquals(OpState.CANCELLED, journal.op(opId)!!.state)

        // سجل ملغى منتهٍ فعلاً: الاستئناف مرفوض (الإعادة من الصفر قرار المستخدم).
        try {
            runBlocking { engine.resume(opId, ConflictPolicy.SKIP) }
            fail()
        } catch (e: java.io.IOException) {
            assertTrue("الرسالة تسمّي الحالة المنتهية: ${e.message}", e.message!!.contains(CoreOpCode.OP_ALREADY_FINISHED))
        }
        assertEquals(OpState.CANCELLED, journal.op(opId)!!.state)
    }
}
