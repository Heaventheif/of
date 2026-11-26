package org.openfilemanager.app.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openfilemanager.core.FileOpJournal
import org.openfilemanager.core.ItemState
import org.openfilemanager.core.JournalRenameAdapter
import org.openfilemanager.core.OpItemKind
import org.openfilemanager.core.OpItemRecord
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecord
import org.openfilemanager.core.OpState
import org.openfilemanager.core.RenameEngine
import org.openfilemanager.core.RenamePhase
import org.openfilemanager.core.RenameStep
import java.nio.file.Files
import java.nio.file.Path

class RecoveryCenterModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: Path
    private lateinit var journal: FileOpJournal
    private lateinit var model: RecoveryCenterModel

    @Before
    fun setUp() {
        files = tmp.newFolder("files").toPath()
        journal = FileOpJournal(tmp.newFolder("ops").toPath())
        model = RecoveryCenterModel(journal)
    }

    /** يسجل كل استدعاء ويعيد [result] — لتمييز «لم يُستدع أبداً» عن «استُدعي ونجح». */
    private class RecordingRequest(var result: Boolean) {
        var invoked = false
        val lambda: (org.openfilemanager.app.OpRequest.Resume) -> Boolean = { _ -> invoked = true; result }
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.write(path, text.toByteArray(Charsets.UTF_8))
    }

    private fun startCopyOrMove(kind: OpKind, opId: String): String {
        journal.startOp(
            OpRecord(opId, kind, OpState.RUNNING, listOf("/src/a.txt"), "/dst", System.currentTimeMillis()),
            listOf(
                OpItemRecord(opId, 0, OpItemKind.FILE, ItemState.PENDING, "/src/a.txt", "/dst/a.txt", group = 0)
            )
        )
        return opId
    }

    /** تسمية جماعية انقطعت بعد أول نقلة إلى المؤقت (نفس بناء اختبار OpRecoveryActions). */
    private fun startInterruptedRename(dir: Path, opId: String): List<RenameStep> {
        write(dir.resolve("a.txt"), "A")
        write(dir.resolve("b.txt"), "B")
        val steps = RenameEngine.plan(dir, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"))
        val adapter = JournalRenameAdapter(journal, opId, steps)
        adapter.begin()
        Files.move(steps[0].from, steps[0].temp)
        adapter.record(RenamePhase.TO_TEMP, steps[0])
        return steps
    }

    // ---- (أ) تسمية منقطعة تُدرج وتُستأنف عبر مسار recoverRename حصراً ----

    @Test
    fun interruptedRenameListedAndResumedThroughRecoverRenameOnly() {
        val root = files
        val opId = "rc-rename"
        startInterruptedRename(root, opId)

        // يظهر في القائمة عند الفحص (recoverableOps).
        val listed = model.load().ops
        assertTrue("العملية المنقطعة يجب أن تُدرج", listed.any { it.id == opId })

        val req = RecordingRequest(true)
        val err = model.resume(opId, req.lambda)
        assertNull("استرداد التسمية يكتمل بلا خطأ", err)
        // القاعدة: RENAME عبر recoverRename فقط — لا طلب خدمة أصلاً.
        assertFalse("استئناف التسمية لا يمر بمسار الخدمة", req.invoked)
        assertEquals("A", String(Files.readAllBytes(root.resolve("x.txt"))))
        assertEquals("B", String(Files.readAllBytes(root.resolve("y.txt"))))
        assertFalse("لا أسماء مؤقتة متبقية",
            Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() }
                .any { it.startsWith(".fm-ren-") })
        // خاتمة مكتوبة FINISHED: الاختفاء من recoverableOps بعد الاسترداد المكتمل.
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        assertFalse("المدخل يُزال من القائمة بعد الاسترداد المكتمل فقط",
            model.load().ops.any { it.id == opId })
    }

    // ---- (ب) نسخ/نقل منقطع يُدرج ويُستأنف بطلب جدولة عبر resolveForJournal ----

    @Test
    fun interruptedCopyListedAndResumedViaResumeRequest() {
        val opId = startCopyOrMove(OpKind.COPY, "rc-copy")
        assertTrue(model.load().ops.any { it.id == opId })

        val req = RecordingRequest(true)
        val err = model.resume(opId, req.lambda)
        assertNull("جدولة الاستئناف الناجحة بلا خطأ", err)
        assertTrue(req.invoked)
        // الاستئناف نفسه لا يغير حالة السجل — الخدمة تكتب الخاتمة عند التنفيذ.
        assertEquals(OpState.RUNNING, journal.op(opId)!!.state)
    }

    @Test
    fun interruptedMoveListedAndResumedViaResumeRequest() {
        val opId = startCopyOrMove(OpKind.MOVE, "rc-move")
        assertTrue(model.load().ops.any { it.id == opId })

        val req = RecordingRequest(true)
        assertNull(model.resume(opId, req.lambda))
        assertTrue(req.invoked)
        assertEquals(OpState.RUNNING, journal.op(opId)!!.state)
    }

    // ---- (ج) الإزالة تتطلب تأكيداً صريحاً ----

    @Test
    fun dismissWithoutConfirmationDoesNotTouchJournal() {
        val opId = startCopyOrMove(OpKind.COPY, "rc-dismiss-n")
        val before = journal.op(opId)!!
        val req = RecordingRequest(true)

        assertNull("بلا تأكيد: لا خطأ ولا أثر", model.dismiss(opId, confirmed = false, req.lambda))
        assertFalse("بلا تأكيد: لا طلب خدمة", req.invoked)
        // السجل لم يُلمس: الحالة نفسها وبلا سطر خاتمة (finishedAt = 0) وما زال مدرجاً.
        assertEquals(before, journal.op(opId))
        assertEquals(0L, journal.op(opId)!!.finishedAt)
        assertTrue("بلا تأكيد: يبقى مدرجاً", model.load().ops.any { it.id == opId })
    }

    @Test
    fun dismissWithConfirmationFinishesEntryAndEmptiesRecoverable() {
        val opId = startCopyOrMove(OpKind.COPY, "rc-dismiss-y")
        val req = RecordingRequest(true)

        assertNull(model.dismiss(opId, confirmed = true, req.lambda))
        // خاتمة دُوّنت (CANCELLED) فاختفي من recoverableOps — لا حذف ملف سجل.
        assertEquals(OpState.CANCELLED, journal.op(opId)!!.state)
        assertNotNull(journal.op(opId))
        assertFalse("بعد التأكيد يُفرغ من القائمة", model.load().ops.any { it.id == opId })
    }

    // ---- (د) الحالة الفارغة ----

    @Test
    fun emptyStateWhenNoRecoverableOperations() {
        val listing = model.load()
        assertTrue("سجل فارغ → قائمة فارغة", listing.ops.isEmpty())
        assertNull(listing.error)
        // سجل منتهٍ ليس منقطعاً: يبقى خارج القائمة.
        startCopyOrMove(OpKind.COPY, "rc-done")
        journal.finishOp("rc-done", OpState.FINISHED, 1, 0, 0, "done")
        assertTrue(model.load().ops.isEmpty())
    }

    // ---- (هـ) العنصر لا يُزال إلا بعد النجاح ----

    @Test
    fun failedResumeKeepsEntryListedWithItsError() {
        val opId = startCopyOrMove(OpKind.COPY, "rc-fail")
        val req = RecordingRequest(false) // فشل الجدولة
        val err = model.resume(opId, req.lambda)
        assertEquals("queue failed", err)
        assertTrue("فشل الاستئناف يُبقي المدخل مدرجاً", model.load().ops.any { it.id == opId })
        assertEquals(OpState.RUNNING, journal.op(opId)!!.state)
    }

    // ---- إضافية: إزالة التسمية المؤكدة تعيد الأسماء الأصلية (مسار RENAME نفسه) ----

    @Test
    fun confirmedDismissOfRenameRestoresOriginalNames() {
        val root = files
        val opId = "rc-rename-d"
        startInterruptedRename(root, opId)

        val req = RecordingRequest(true)
        assertNull(model.dismiss(opId, confirmed = true, req.lambda))
        assertFalse("إزالة التسمية لا تمر بالخدمة أيضاً", req.invoked)
        assertEquals("A", String(Files.readAllBytes(root.resolve("a.txt"))))
        assertEquals("B", String(Files.readAllBytes(root.resolve("b.txt"))))
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        assertFalse(model.load().ops.any { it.id == opId })
    }
}
