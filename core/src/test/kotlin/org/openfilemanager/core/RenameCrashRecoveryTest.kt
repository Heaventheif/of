package org.openfilemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class RenameCrashRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private class SimulatedProcessDeath : Error("process killed mid-operation")

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun names(root: Path): Set<String> =
        Files.list(root).use { s -> s.map { it.fileName.toString() }.toList().toSet() }

    private fun noTempNames(root: Path): Boolean = names(root).none { it.startsWith(".fm-ren-") }

    /** سجل حقيقي (ملفات) يُوكل التسجيل ثم يقتل العملية بعد مرور [dieAfter] تسجيلاً. */
    private class DyingJournal(
        delegate: OpJournal,
        private val opId: String,
        private val steps: List<RenameStep>,
        private val dieAfter: Int
    ) : OpJournal by delegate, RenameJournal {
        private val adapter = JournalRenameAdapter(delegate, opId, steps)
        private var seen = 0
        fun begin() = adapter.begin()
        override fun record(phase: RenamePhase, step: RenameStep) {
            adapter.record(phase, step)
            seen++
            if (seen == dieAfter) throw SimulatedProcessDeath()
        }
    }

    /** ثلاثة ملفات ومحتواها: الحقيقة المرجعية التي يجب أن تبقى بعد أي استرداد. */
    private fun setupThree(dir: Path) {
        write(dir.resolve("a.txt"), "A")
        write(dir.resolve("b.txt"), "B")
        write(dir.resolve("c.txt"), "C")
    }

    private fun expected(): Map<String, String> =
        mapOf("x.txt" to "A", "y.txt" to "B", "z.txt" to "C")

    private fun originals(): Map<String, String> =
        mapOf("a.txt" to "A", "b.txt" to "B", "c.txt" to "C")

    private fun journalDir(name: String): Path = tmp.newFolder(name).toPath()

    private fun assertJournalAlive(journal: FileOpJournal, opId: String, steps: Int) {
        val op = journal.op(opId)
        assertNotNull("قيد سجل التسمية يجب أن يبقى بعد الموت المفاجئ", op)
        assertEquals("الموت المفاجئ لا يكتب سطر إنهاء", OpState.RUNNING, op!!.state)
        assertEquals("كل خطوة مدوّنة كعنصر", steps, journal.items(opId).size)
    }

    private fun assertComplete(dir: Path, journal: FileOpJournal, opId: String) {
        assertEquals("لا ملف ضائع ولا مكتوب فوقه", expected(), expected().mapValues { (n, _) -> Files.readString(dir.resolve(n)) })
        assertTrue("لا أسماء مؤقتة متبقية", noTempNames(dir))
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    private fun assertRestored(dir: Path, journal: FileOpJournal, opId: String) {
        assertEquals("الأصول كلها بأسمائها الأصلية وبمحتواها", originals(), originals().mapValues { (n, _) -> Files.readString(dir.resolve(n)) })
        assertTrue("لا أسماء مؤقتة متبقية", noTempNames(dir))
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    private fun renameSteps(dir: Path): List<RenameStep> =
        RenameEngine.plan(dir, listOf("a.txt" to "x.txt", "b.txt" to "y.txt", "c.txt" to "z.txt"))

    // ---- موت بعد أول نقلة إلى المؤقت ----
    @Test
    fun killAfterFirstTempMoveThenComplete() {
        val d = dir("k1")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k1-ops")
        val journal = DyingJournal(FileOpJournal(jd), "crash1", steps, dieAfter = 1)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        val restart = FileOpJournal(jd)
        assertJournalAlive(restart, "crash1", steps.size)
        assertTrue("الخطوة 0 عند اسمها المؤقت", Files.exists(steps[0].temp))
        assertFalse(Files.exists(steps[0].from))

        OpRecovery.recoverRename(restart, "crash1", complete = true)
        assertComplete(d, restart, "crash1")
    }

    @Test
    fun killAfterFirstTempMoveThenRollBack() {
        val d = dir("k1r")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k1r-ops")
        val journal = DyingJournal(FileOpJournal(jd), "crash1r", steps, dieAfter = 1)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        val restart = FileOpJournal(jd)
        OpRecovery.recoverRename(restart, "crash1r", complete = false)
        assertRestored(d, restart, "crash1r")
    }

    @Test
    fun killBetweenTempMovesThenComplete() {
        val d = dir("k2")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k2-ops")
        val journal = DyingJournal(FileOpJournal(jd), "crash2", steps, dieAfter = 2)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        val restart = FileOpJournal(jd)
        assertJournalAlive(restart, "crash2", steps.size)
        assertTrue(Files.exists(steps[0].temp))
        assertTrue(Files.exists(steps[1].temp))
        assertFalse(Files.exists(steps[2].temp))
        assertTrue("ما لم يُلمس ما زال أصله موجوداً", Files.exists(steps[2].from))

        OpRecovery.recoverRename(restart, "crash2", complete = true)
        assertComplete(d, restart, "crash2")
    }

    @Test
    fun killDuringFinalPhaseThenComplete() {
        val d = dir("k3")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k3-ops")
        // الموت بعد تسجيل TO_FINAL الأول: خطوة 0 نهائية، 1-2 مؤقتة.
        val journal = DyingJournal(FileOpJournal(jd), "crash3", steps, dieAfter = 4)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        val restart = FileOpJournal(jd)
        assertJournalAlive(restart, "crash3", steps.size)
        assertTrue(Files.exists(steps[0].to))

        OpRecovery.recoverRename(restart, "crash3", complete = true)
        assertComplete(d, restart, "crash3")
    }

    @Test
    fun killDuringFinalPhaseRollBackLeavesFinishedItemAtFinalName() {
        val d = dir("k3r")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k3r-ops")
        val journal = DyingJournal(FileOpJournal(jd), "crash3r", steps, dieAfter = 4)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        val restart = FileOpJournal(jd)
        val recovery = OpRecovery.recoverRename(restart, "crash3r", complete = false)
        // ما وصل نهائيه قبل الموت يبقى نهائياً (موثق): البقية تعود للأصل.
        assertEquals("العنصر النهائي يبقى كما هو", 1, recovery.leftAsIs)
        assertEquals("البقية تعود للأصل", 2, recovery.restored)
        assertEquals("A", Files.readString(d.resolve("x.txt")))
        assertEquals("B", Files.readString(d.resolve("b.txt")))
        assertEquals("C", Files.readString(d.resolve("c.txt")))
        assertTrue(noTempNames(d))
        assertEquals(OpState.FINISHED, restart.op("crash3r")!!.state)
    }

    // ---- موت وسط الاسترداد نفسه: إعادة الدخول ----
    @Test
    fun killDuringRecoveryIsReEntrant() {
        val d = dir("k4")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k4-ops")
        val journal = DyingJournal(FileOpJournal(jd), "crash4", steps, dieAfter = 2)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        // استرداد يموت بعد أول تحديث عنصر (النقلة تمت والتحديث لم يُدوَّن).
        val dyingRecovery = object : OpJournal by FileOpJournal(jd) {
            var calls = 0
            override fun updateItem(opId: String, index: Int, state: ItemState, extra: String, error: String, dst: String) {
                if (calls++ == 0) throw SimulatedProcessDeath()
            }
        }
        try { OpRecovery.recoverRename(dyingRecovery, "crash4", complete = true) } catch (_: SimulatedProcessDeath) {}

        // «إقلاع» جديد: سجل جديد فوق المجلد نفسه يكمل الاسترداد.
        val restart = FileOpJournal(jd)
        OpRecovery.recoverRename(restart, "crash4", complete = true)
        assertComplete(d, restart, "crash4")
    }

    // ---- الاسترداد مُعاد الدخول أيضاً بلا موت: تشغيلان متتاليان ----
    @Test
    fun doubleRecoveryIsIdempotent() {
        val d = dir("k5")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k5-ops")
        val journal = DyingJournal(FileOpJournal(jd), "crash5", steps, dieAfter = 2)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        val restart = FileOpJournal(jd)
        val first = OpRecovery.recoverRename(restart, "crash5", complete = true)
        val second = OpRecovery.recoverRename(restart, "crash5", complete = true)
        assertEquals(3, first.finished)
        assertEquals(0, second.finished)
        assertEquals(3, second.leftAsIs)
        assertComplete(d, restart, "crash5")
    }

    // ---- لا تُكتب فوق وجهة ظهرت بين الموت والاسترداد (إرجاع) ----
    @Test
    fun rollbackNeverOverwritesOccupiedDestination() {
        val d = dir("k6")
        setupThree(d)
        val steps = renameSteps(d)
        val jd = journalDir("k6-ops")
        val journal = DyingJournal(FileOpJournal(jd), "crash6", steps, dieAfter = 2)
        journal.begin()
        try { RenameEngine.execute(steps, journal) } catch (_: SimulatedProcessDeath) {}

        // «مستخدم آخر» حجز الوجهة النهائية للخطوة 0 قبل الاسترداد.
        write(d.resolve("x.txt"), "stranger data")

        val restart = FileOpJournal(jd)
        val recovery = OpRecovery.recoverRename(restart, "crash6", complete = true)
        assertEquals("بيانات الغريب بلا مساس", "stranger data", Files.readString(d.resolve("x.txt")))
        assertTrue("المصدر الأصلي عاد لأن الوجهة محجوزة", Files.exists(d.resolve("a.txt")))
        assertEquals("A", Files.readString(d.resolve("a.txt")))
        assertTrue("لا مؤقتات متبقية", noTempNames(d))
        assertEquals(OpState.FINISHED, restart.op("crash6")!!.state)
        assertTrue(recovery.errors.isNotEmpty() || recovery.restored > 0 || recovery.leftAsIs > 0)
    }
}
