package org.openfilemanager.app.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openfilemanager.app.OpRecoveryActions
import org.openfilemanager.core.FileOpJournal
import org.openfilemanager.core.ItemState
import org.openfilemanager.core.OpJournal
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecovery
import org.openfilemanager.core.OpState
import org.openfilemanager.core.RenameEngine
import org.openfilemanager.core.RenameJournal
import org.openfilemanager.core.RenameOutcome
import org.openfilemanager.core.RenameStep
import java.nio.file.Files
import java.nio.file.Path

class RenameCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class SimulatedProcessDeath : Error("process killed mid-operation")

    private lateinit var files: Path
    private lateinit var journal: FileOpJournal

    @Before
    fun setUp() {
        files = tmp.newFolder("files").toPath()
        journal = FileOpJournal(tmp.newFolder("ops").toPath())
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.write(path, text.toByteArray(Charsets.UTF_8))
    }

    private fun contents(): Map<String, String> =
        Files.list(files).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
                .associate { it.fileName.toString() to String(Files.readAllBytes(it), Charsets.UTF_8) }
        }

    private fun names(): Set<String> =
        Files.list(files).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }

    /**
     * سجل يفوّض كل شيء لسجل حقيقي ويعدّ استدعاءات updateItem (بترتيبها:
     * TO_TEMP لكل خطوة ثم TO_FINAL لكل خطوة) مع خطاف يشتغل عند العدد المعين.
     */
    private class CountingJournal(delegate: FileOpJournal) : OpJournal by delegate {
        val underlying = delegate
        var calls = 0
        var onCall: ((Int) -> Unit)? = null
        override fun updateItem(opId: String, index: Int, state: ItemState, extra: String, error: String, dst: String) {
            calls++
            onCall?.invoke(calls)
            underlying.updateItem(opId, index, state, extra, error, dst)
        }
    }

    // ---- النجاح ----
    @Test
    fun successFinishesTheJournalRecord() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")

        val outcome = RenameCoordinator(journal).execute(
            files,
            listOf("a.txt" to "x.txt", "b.txt" to "y.txt"),
            opId = "coord-ok"
        )

        assertTrue(outcome is RenameOutcome.Success)
        assertEquals(2, (outcome as RenameOutcome.Success).steps.size)
        assertEquals(OpState.FINISHED, journal.op("coord-ok")!!.state)
        assertEquals(setOf("x.txt", "y.txt"), names())
        assertTrue("لا مؤقتات متبقية", names().none { it.startsWith(".fm-ren-") })
        assertTrue(OpRecovery.recoverableOps(journal.ops()).isEmpty())
    }

    // ---- تعارض حقيقي (وجهة محجوزة خارج الدفعة): فشل متولى والقيد FAILED ----
    @Test
    fun conflictIsAHandledFailureAndLeavesNoRunningRecord() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("target.txt"), "T")

        val outcome = RenameCoordinator(journal).execute(
            files,
            listOf("a.txt" to "target.txt"),
            opId = "coord-conflict"
        )

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals(OpState.FAILED, journal.op("coord-conflict")!!.state)
        assertEquals(mapOf("a.txt" to "A", "target.txt" to "T"), contents())
        assertTrue(OpRecovery.recoverableOps(journal.ops()).isEmpty())
    }

    // ---- فشل متولى بحقن خلوّل المحرك: القيد FAILED والأسماء عادت ----
    @Test
    fun handledFailureMarksRecordFailedAndRestoresNames() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val base = FileOpJournal(tmp.newFolder("ops-handled").toPath())
        val counting = CountingJournal(base).apply {
            // أول تحديث (TO_TEMP للخطوة 0) يرمي استثناءً عادياً: يلتقطه المحرك
            // فيبدأ التراجع الداخلي، ثم ينهي المنسق القيد FAILED.
            onCall = { throw java.io.IOException("injected failure") }
        }

        val outcome = RenameCoordinator(counting).execute(
            files,
            listOf("a.txt" to "x.txt", "b.txt" to "y.txt"),
            opId = "coord-fail"
        )

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals(setOf("a.txt", "b.txt"), names())
        assertEquals("A", String(Files.readAllBytes(files.resolve("a.txt"))))
        assertEquals(OpState.FAILED, base.op("coord-fail")!!.state)
        assertTrue(OpRecovery.recoverableOps(base.ops()).none { it.id == "coord-fail" })
    }

    // ---- موت مفاجئ (Error يفلت من التقاط المحرك): القيد يبقى RUNNING ----
    @Test
    fun abruptDeathLeavesRecordRunningAndRecoveryCompletes() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val opsDir = tmp.newFolder("ops-death").toPath()
        val base = FileOpJournal(opsDir)
        val counting = CountingJournal(base).apply {
            onCall = { throw SimulatedProcessDeath() }
        }

        try {
            RenameCoordinator(counting).execute(
                files,
                listOf("a.txt" to "x.txt", "b.txt" to "y.txt"),
                opId = "coord-death"
            )
            fail("الموت المفاجئ يجب أن ينتشر")
        } catch (_: SimulatedProcessDeath) {
        }

        // «إقلاع» جديد: سجل جديد فوق المجلد نفسه يكشف القيد ويكمل الأسماء.
        val restart = FileOpJournal(opsDir)
        assertTrue("القيد المتروك يظهر للاسترداد",
            OpRecovery.recoverableOps(restart.ops()).any { it.id == "coord-death" })
        assertTrue("الخطوة الأولى عند اسمها المؤقت", names().any { it.startsWith(".fm-ren-") })

        val recovery = OpRecovery.recoverRename(restart, "coord-death", complete = true)
        assertEquals(2, recovery.finished)
        assertEquals(setOf("x.txt", "y.txt"), names())
        assertEquals(OpState.FINISHED, restart.op("coord-death")!!.state)
        assertTrue(names().none { it.startsWith(".fm-ren-") })
    }

    // ---- فشل غير متولى (التراجع نفسه فشل): القيد يبقى RUNNING بلا إنهاء ----
    @Test
    fun failedRollbackLeavesRecordRunningForRecovery() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val base = FileOpJournal(tmp.newFolder("ops-noroll").toPath())
        // المسارات الحتمية (من/إلى) مستقلة عن opId العشوائي للمؤقتات:
        val steps: List<RenameStep> = RenameEngine.plan(
            files, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), "coord-noroll"
        )
        val counting = CountingJournal(base).apply {
            onCall = { n ->
                // الاستدعاء 3 = تسجيل TO_FINAL للخطوة 0. بعده مباشرة:
                // نقلة TO_FINAL للخطوة 1 ستفشل (وجهتها محجوزة) ويرمي المحرك،
                // وتراجعه (المؤقت ← الأصل للخطوة 0) سيفشل أيضاً (الأصل محجوز).
                if (n == 3) {
                    write(steps[1].to, "blocker")
                    write(steps[0].from, "stranger")
                }
            }
        }

        val outcome = RenameCoordinator(counting).execute(
            files,
            listOf("a.txt" to "x.txt", "b.txt" to "y.txt"),
            opId = "coord-noroll"
        )

        assertTrue(outcome is RenameOutcome.Failure)
        assertFalse("تراجع المحرك فشل فعلاً: ملف أجنبي حجز مسار الأصل",
            (outcome as RenameOutcome.Failure).rolledBack)
        assertEquals("القيد لا يُنهى والقرص مجهول: يبقى RUNNING",
            OpState.RUNNING, base.op("coord-noroll")!!.state)
        assertTrue(OpRecovery.recoverableOps(base.ops()).any { it.id == "coord-noroll" })
        // لا ملف ضائع ولا بيانات مكتوبة فوقها: محتوى A عند مؤقتٍ سيُستردّ،
        // وبيانات الغريب عند الأصل بلا مساس، وB عاد لأصله.
        assertEquals("B", String(Files.readAllBytes(files.resolve("b.txt"))))
        assertEquals("stranger", String(Files.readAllBytes(files.resolve("a.txt"))))
        val temps = names().filter { it.startsWith(".fm-ren-") }
        assertTrue("محتوى A محفوظ عند مؤقت المتروك", temps.isNotEmpty())
        assertEquals("A", String(Files.readAllBytes(files.resolve(temps.first()))))
        // قرار إلغاء المستخدم على القيد المجهول: مسار RENAME يمر عبر
        // recoverRename حصراً (لا جدولة للخدمة) وهي تدوّن خاتمتها FINISHED
        // دائماً بالعدّادات — حالة CANCELLED للعمليات الأخرى فقط.
        val err = OpRecoveryActions.resolveForJournal(
            base, base.op("coord-noroll")!!, resume = false, resumeRequest = { true }
        )
        assertNull(err)
        assertEquals(OpState.FINISHED, base.op("coord-noroll")!!.state)
    }

    // ---- قيد متروك يُستكمل عبر سطح الاسترداد الحالي (OpRecoveryActions) ----
    @Test
    fun interruptedRenameIsResumedThroughOpRecoveryActions() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val opId = "coord-resume"
        crashAfter(opId, RenameEngine.plan(files, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), opId), dieAt = 1)

        val interrupted = journal.op(opId)!!
        assertEquals(OpKind.RENAME, interrupted.kind)
        assertTrue(OpRecovery.recoverableOps(journal.ops()).any { it.id == opId })

        val error = OpRecoveryActions.resolveForJournal(
            journal, interrupted, resume = true, resumeRequest = { true }
        )

        assertNull(error)
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        assertEquals(mapOf("x.txt" to "A", "y.txt" to "B"), contents())
        assertTrue(names().none { it.startsWith(".fm-ren-") })
    }

    @Test
    fun interruptedRenameIsRolledBackThroughOpRecoveryActions() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val opId = "coord-cancel"
        crashAfter(opId, RenameEngine.plan(files, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), opId), dieAt = 1)

        val error = OpRecoveryActions.resolveForJournal(
            journal, journal.op(opId)!!, resume = false, resumeRequest = { true }
        )

        assertNull(error)
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        assertEquals(mapOf("a.txt" to "A", "b.txt" to "B"), contents())
        assertTrue(names().none { it.startsWith(".fm-ren-") })
    }

    // ---- E5: رسائل السجل رموز مستقلة عن اللغة ----

    /**
     * السجل بيانات لا واجهة: أي عربي فيه يظهر بلا ترجمة للمستخدم الإنجليزي
     * (وعلى أي أدوات خارجية تقرأ الملف). الترجمات مسؤولية strings.xml.
     * يُثبَّت للأمسار الثلاثة: نجاح، تراجع، فشل متولّى.
     */
    @Test
    fun journalMessagesAreLocaleNeutralCodes_PinsE5() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        write(files.resolve("target.txt"), "T")

        RenameCoordinator(journal).execute(
            files, listOf("a.txt" to "x.txt"), opId = "e5-ok"
        )
        assertEquals("BULK_RENAME", journal.op("e5-ok")!!.message)

        val done = (RenameCoordinator(journal).execute(
            files, listOf("x.txt" to "y.txt"), opId = "e5-fwd"
        ) as RenameOutcome.Success).steps
        RenameCoordinator(journal).executeUndo(done, opId = "e5-undo")
        assertEquals("BULK_RENAME_UNDO", journal.op("e5-undo")!!.message)

        // فشل متولّى (وجهة محجوزة): رمز الفشل فقط — لا نص عربي ولا رسالة
        // السبب داخل السجل (السبب يُسجَّل في logcat ويصل عبر Outcome.cause).
        val outcome = RenameCoordinator(journal).execute(
            files, listOf("y.txt" to "target.txt"), opId = "e5-fail"
        )
        assertTrue(outcome is RenameOutcome.Failure)
        assertEquals("BULK_RENAME_ROLLED_BACK", journal.op("e5-fail")!!.message)
    }

    /** كل رسائل قيود المنسق بلا محارف عربية (تحوّط أي مسار يُضاف لاحقاً). */
    @Test
    fun coordinatorJournalMessagesContainNoArabic() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("target.txt"), "T")

        RenameCoordinator(journal).execute(files, listOf("a.txt" to "x.txt"), opId = "ar-1")
        val done = (RenameCoordinator(journal).execute(
            files, listOf("x.txt" to "y.txt"), opId = "ar-2"
        ) as RenameOutcome.Success).steps
        RenameCoordinator(journal).executeUndo(done, opId = "ar-3")
        RenameCoordinator(journal).execute(files, listOf("y.txt" to "target.txt"), opId = "ar-4")

        for (op in journal.ops()) {
            assertTrue(
                "رسالة قيد ${op.id} تحوي محارف عربية: \"${op.message}\"",
                op.message.none { it in '\u0600'..'\u06FF' }
            )
        }
    }

    // ---- E1: التراجع يمر عبر الجورنال (كان يتجاوزه في BrowserViewModel) ----

    /** التوصيف قبل أي إصلاح: التراجع المباشر بالمحرك بلا جورنال لا يترك قيداً
     *  يستردّه أحد — هذا هو العيب E1 نفسه موثّقاً على مستوى المحرك. */
    @Test
    fun engineOnlyUndoDeathLeavesNoRecoveryRecord_PinsE1Gap() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val done = (RenameCoordinator(journal).execute(
            files, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), opId = "pin-fwd"
        ) as RenameOutcome.Success).steps

        // تراجع مباشر بلا adapter (المسار القديم في BrowserViewModel):
        // نحاكي موت العملية بإلقاء Error من جورنال وهمي بعد أول نقلة ناجحة.
        val dying = RenameJournal { _, _ -> throw SimulatedProcessDeath() }
        try {
            RenameEngine.execute(RenameEngine.undoSteps(done), dying)
            fail("الموت المفاجئ يجب أن ينتشر")
        } catch (_: SimulatedProcessDeath) {
        }

        // record يُستدعى بعد move الناجحة، فمحتوى الخطوة 0 صار في مؤقتها
        // والقرص بلا أي قيد جديد يسترده أحد.
        assertTrue(names().any { it.startsWith(".fm-ren-") })
        assertFalse(
            "المسار القديم لا يترك قيداً قابلاً للاسترداد (العيب E1)",
            OpRecovery.recoverableOps(journal.ops()).any { it.id != "pin-fwd" }
        )
    }

    @Test
    fun undoThroughCoordinatorSucceedsAndIsJournaled() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val done = (RenameCoordinator(journal).execute(
            files, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), opId = "undo-fwd"
        ) as RenameOutcome.Success).steps

        val outcome = RenameCoordinator(journal).executeUndo(done, opId = "undo-ok")

        assertTrue(outcome is RenameOutcome.Success)
        assertEquals(2, (outcome as RenameOutcome.Success).steps.size)
        assertEquals(setOf("a.txt", "b.txt"), names())
        assertEquals(mapOf("a.txt" to "A", "b.txt" to "B"), contents())
        assertEquals(OpState.FINISHED, journal.op("undo-ok")!!.state)
        assertTrue(names().none { it.startsWith(".fm-ren-") })
        assertTrue(OpRecovery.recoverableOps(journal.ops()).isEmpty())
    }

    /** موت العملية أثناء التراجع (بعد TO_TEMP): القيد RUNNING والاسترداد يُتمّ التراجع. */
    @Test
    fun undoAbruptDeathLeavesRecoverableRecordAndRecoveryCompletesUndo() {
        write(files.resolve("a.txt"), "A")
        write(files.resolve("b.txt"), "B")
        val done = (RenameCoordinator(journal).execute(
            files, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), opId = "undo-fwd2"
        ) as RenameOutcome.Success).steps
        val opsDir = tmp.newFolder("ops-undo-death").toPath()
        val base = FileOpJournal(opsDir)
        val counting = CountingJournal(base).apply { onCall = { throw SimulatedProcessDeath() } }

        try {
            RenameCoordinator(counting).executeUndo(done, opId = "undo-death")
            fail("الموت المفاجئ يجب أن ينتشر")
        } catch (_: SimulatedProcessDeath) {
        }

        val restart = FileOpJournal(opsDir)
        assertTrue(
            "القيد المتروك أثناء التراجع يظهر للاسترداد",
            OpRecovery.recoverableOps(restart.ops()).any { it.id == "undo-death" }
        )
        assertTrue(names().any { it.startsWith(".fm-ren-") })

        val recovery = OpRecovery.recoverRename(restart, "undo-death", complete = true)
        assertEquals(2, recovery.finished)
        assertEquals(setOf("a.txt", "b.txt"), names())
        assertEquals(mapOf("a.txt" to "A", "b.txt" to "B"), contents())
        assertEquals(OpState.FINISHED, restart.op("undo-death")!!.state)
        assertTrue(names().none { it.startsWith(".fm-ren-") })
    }

    /** تعارض أثناء التراجع (الاسم الأصلي محجوز بملف أجنبي): فشل متولى والقيد FAILED. */
    @Test
    fun undoConflictIsAHandledFailureAndLeavesNoRunningRecord() {
        write(files.resolve("a.txt"), "A")
        val done = (RenameCoordinator(journal).execute(
            files, listOf("a.txt" to "x.txt"), opId = "undo-fwd3"
        ) as RenameOutcome.Success).steps
        write(files.resolve("a.txt"), "stranger")

        val outcome = RenameCoordinator(journal).executeUndo(done, opId = "undo-conflict")

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals(OpState.FAILED, journal.op("undo-conflict")!!.state)
        assertEquals("A", String(Files.readAllBytes(files.resolve("x.txt"))))
        assertEquals("stranger", String(Files.readAllBytes(files.resolve("a.txt"))))
        assertTrue(OpRecovery.recoverableOps(journal.ops()).none { it.id == "undo-conflict" })
    }

    private fun crashAfter(opId: String, steps: List<RenameStep>, dieAt: Int) {
        val adapter = org.openfilemanager.core.JournalRenameAdapter(journal, opId, steps)
        adapter.begin()
        var calls = 0
        val dying = RenameJournal { phase, step ->
            adapter.record(phase, step)
            calls++
            if (calls == dieAt) throw SimulatedProcessDeath()
        }
        try {
            RenameEngine.execute(steps, dying)
        } catch (_: SimulatedProcessDeath) {
            return
        }
        throw AssertionError("the simulated process death did not happen")
    }
}
