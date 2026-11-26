package org.openfilemanager.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openfilemanager.core.FileOpJournal
import org.openfilemanager.core.ItemState
import org.openfilemanager.core.OpItemKind
import org.openfilemanager.core.OpItemRecord
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecord
import org.openfilemanager.core.OpRecovery
import org.openfilemanager.core.OpState

/** اختبارات منطق قرار الاسترداد. */
class OpRecoveryActionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun journal(): FileOpJournal = FileOpJournal(tmp.newFolder("ops").toPath())

    private fun copyRecord(opId: String, state: OpState): OpRecord =
        OpRecord(opId, OpKind.COPY, state, listOf("/src/a.txt"), "/dst", System.currentTimeMillis())

    private fun startCopy(journal: FileOpJournal, opId: String, state: OpState) {
        journal.startOp(
            copyRecord(opId, state),
            listOf(
                OpItemRecord(opId, 0, OpItemKind.FILE, ItemState.PENDING, "/src/a.txt", "/dst/a.txt", group = 0)
            )
        )
    }

    /** يسجل كل استدعاء ويعيد [result] — لتمييز «لم يُستدع أبداً» عن «استدعِي ونجح». */
    private class RecordingRequest(var result: Boolean) {
        var invoked = false
        val lambda: (OpRequest.Resume) -> Boolean = { _ -> invoked = true; result }
    }

    @Test
    fun resumeOfRunningOperationDispatchesRequest() {
        val journal = journal()
        val opId = "resume-ok"
        startCopy(journal, opId, OpState.RUNNING)
        val req = RecordingRequest(true)
        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = true, resumeRequest = req.lambda)
        assertNull("successful dispatch must produce no error", err)
        assertTrue(req.invoked)
        // الاستئناف نفسه لا يغير حالة السجل — الخدمة تغيّرها عند التنفيذ.
        assertEquals(OpState.RUNNING, journal.op(opId)!!.state)
    }

    @Test
    fun resumeOfFinishedOperationIsRefusedWithoutDispatch() {
        val journal = journal()
        val opId = "already-done"
        startCopy(journal, opId, OpState.RUNNING)
        journal.finishOp(opId, OpState.FINISHED, 1, 0, 0, "done")
        val req = RecordingRequest(true)
        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = true, resumeRequest = req.lambda)
        assertNotNull("double-resume must be refused with an explicit error", err)
        assertTrue(err!!.contains("already finished"))
        assertFalse("no second request must be dispatched", req.invoked)
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    @Test
    fun resumeOfCancelledOperationIsRefused() {
        val journal = journal()
        val opId = "cancelled-op"
        startCopy(journal, opId, OpState.RUNNING)
        journal.finishOp(opId, OpState.CANCELLED, 0, 0, 1, "user cancelled")
        val req = RecordingRequest(true)
        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = true, resumeRequest = req.lambda)
        assertNotNull(err)
        assertTrue(err!!.contains("already cancelled"))
        assertFalse(req.invoked)
    }

    @Test
    fun queueFailureIsReportedNotSwallowed() {
        val journal = journal()
        val opId = "queue-fail"
        startCopy(journal, opId, OpState.RUNNING)
        val req = RecordingRequest(false)
        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = true, resumeRequest = req.lambda)
        assertEquals("queue failed", err)
        assertTrue(req.invoked)
        assertEquals(OpState.RUNNING, journal.op(opId)!!.state)
    }

    @Test
    fun cancelOfRunningOperationMarksCancelled() {
        val journal = journal()
        val opId = "cancel-ok"
        startCopy(journal, opId, OpState.RUNNING)
        val req = RecordingRequest(true)
        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = false, resumeRequest = req.lambda)
        assertNull(err)
        assertEquals(OpState.CANCELLED, journal.op(opId)!!.state)
    }

    @Test
    fun cancelOfFinishedOperationIsANoOpWithoutDuplicateFinish() {
        val journal = journal()
        val opId = "finished-noop"
        startCopy(journal, opId, OpState.RUNNING)
        journal.finishOp(opId, OpState.FINISHED, 1, 0, 0, "done")
        val before = journal.op(opId)!!
        val err = OpRecoveryActions.resolveForJournal(journal, before, resume = false, resumeRequest = RecordingRequest(true).lambda)
        // قرار بلا أثر على سجل منتهٍ: لا خطأ، ولا سطر إنهاء جديد، ولا مسح حالة.
        assertNull(err)
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        assertEquals(before.finishedAt, journal.op(opId)!!.finishedAt)
    }

    @Test
    fun missingJournalRecordIsAnExplicitError() {
        val journal = journal()
        val req = RecordingRequest(true)
        val err = OpRecoveryActions.resolveForJournal(journal, copyRecord("ghost", OpState.RUNNING), resume = true, resumeRequest = req.lambda)
        assertNotNull(err)
        assertTrue(err!!.contains("no journal record"))
        assertFalse(req.invoked)
    }

    @Test
    fun renameDecisionFinishesTheRenameRecord() {
        val journal = journal()
        val opId = "rename-op"
        journal.startOp(
            OpRecord(opId, OpKind.RENAME, OpState.RUNNING, emptyList(), "", System.currentTimeMillis()),
            emptyList()
        )
        val req = RecordingRequest(true)
        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = true, resumeRequest = req.lambda)
        assertNull("empty rename recovery completes cleanly", err)
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    /** يبني قيد تسمية انقطعت بعد أول نقلة إلى المؤقت، بجسر JournalRenameAdapter نفسه. */
    private fun startInterruptedRename(
        journal: FileOpJournal,
        dir: java.nio.file.Path,
        steps: List<org.openfilemanager.core.RenameStep>
    ): String {
        val opId = "interrupted-rename"
        val adapter = org.openfilemanager.core.JournalRenameAdapter(journal, opId, steps)
        adapter.begin()
        // نقلة الخطوة 0 إلى المؤقت ثم تسجيلها، ثم «موت»: بلا تراجع وبلا إنهاء.
        java.nio.file.Files.move(steps[0].from, steps[0].temp)
        adapter.record(org.openfilemanager.core.RenamePhase.TO_TEMP, steps[0])
        return opId
    }

    @Test
    fun interruptedBulkRenameIsPickedUpAndCompletesOnResume() {
        val root = tmp.newFolder("rename-c").toPath()
        java.nio.file.Files.write(root.resolve("a.txt"), "A".toByteArray())
        java.nio.file.Files.write(root.resolve("b.txt"), "B".toByteArray())
        val steps = org.openfilemanager.core.RenameEngine.plan(
            root, listOf("a.txt" to "x.txt", "b.txt" to "y.txt")
        )
        val journal = journal()
        val opId = startInterruptedRename(journal, root, steps)

        // يظهر في العمليات الجديرة بالاسترداد عند الإقلاع.
        assertTrue(OpRecovery.recoverableOps(journal.ops()).any { it.id == opId })

        val req = RecordingRequest(true)
        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = true, resumeRequest = req.lambda)
        assertNull(err)
        assertEquals("A", String(java.nio.file.Files.readAllBytes(root.resolve("x.txt"))))
        assertEquals("B", String(java.nio.file.Files.readAllBytes(root.resolve("y.txt"))))
        assertFalse("لا أسماء مؤقتة متبقية",
            java.nio.file.Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() }
                .any { it.startsWith(".fm-ren-") })
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        // القرار عبر مسار RENAME الخاص: لا يُرسل للخدمة أصلاً (المحرك العام يرفض استئناف التسمية).
        assertFalse(req.invoked)
    }

    @Test
    fun interruptedBulkRenameRestoresOriginalsOnCancel() {
        val root = tmp.newFolder("rename-r").toPath()
        java.nio.file.Files.write(root.resolve("a.txt"), "A".toByteArray())
        java.nio.file.Files.write(root.resolve("b.txt"), "B".toByteArray())
        val steps = org.openfilemanager.core.RenameEngine.plan(
            root, listOf("a.txt" to "x.txt", "b.txt" to "y.txt")
        )
        val journal = journal()
        val opId = startInterruptedRename(journal, root, steps)

        val err = OpRecoveryActions.resolveForJournal(journal, journal.op(opId)!!, resume = false, resumeRequest = RecordingRequest(true).lambda)
        assertNull(err)
        assertEquals("A", String(java.nio.file.Files.readAllBytes(root.resolve("a.txt"))))
        assertEquals("B", String(java.nio.file.Files.readAllBytes(root.resolve("b.txt"))))
        assertFalse("لا أسماء مؤقتة متبقية",
            java.nio.file.Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() }
                .any { it.startsWith(".fm-ren-") })
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }
}
