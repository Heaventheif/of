package org.openfilemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class RenameEngineCharacterizationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun names(root: Path): Set<String> =
        Files.list(root).use { s -> s.map { it.fileName.toString() }.toList().toSet() }

    private fun content(root: Path, name: String): String = Files.readString(root.resolve(name))

    /** يسجل المراحل ويمرر الاختياري [hook] بعد كل تسجيل. */
    private class RecordingJournal(
        private val delegate: RenameJournal? = null,
        private val hook: ((RenamePhase, RenameStep) -> Unit)? = null
    ) : RenameJournal {
        val records = ArrayList<Pair<RenamePhase, RenameStep>>()
        override fun record(phase: RenamePhase, step: RenameStep) {
            records += phase to step
            delegate?.record(phase, step)
            hook?.invoke(phase, step)
        }
    }

    // ---- النجاح العادي ----
    @Test
    fun successRenamesAllStepsAndLeavesNoTempNames() {
        val d = dir("ok")
        write(d.resolve("a.txt"), "A")
        write(d.resolve("b.txt"), "B")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"))
        val journal = RecordingJournal()

        val outcome = RenameEngine.execute(steps, journal)

        assertTrue(outcome is RenameOutcome.Success)
        assertEquals(setOf("x.txt", "y.txt"), names(d))
        assertEquals("A", content(d, "x.txt"))
        assertEquals("B", content(d, "y.txt"))
        // كل خطوة سُجّلت مرتين: إلى المؤقت ثم إلى النهائي.
        assertEquals(steps.size * 2, journal.records.size)
        assertEquals(RenamePhase.TO_TEMP, journal.records.first().first)
        assertEquals(RenamePhase.TO_FINAL, journal.records.last().first)
        assertFalse(names(d).any { it.startsWith(".fm-ren-") })
    }

    // ---- التعارض: الوجهة محجوزة بعنصر خارج الدفعة ----
    @Test
    fun conflictTargetOccupiedOutsideBatchIsRejectedBeforeAnyMove() {
        val d = dir("conflict")
        write(d.resolve("a.txt"), "A")
        write(d.resolve("occupied.txt"), "other data")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "occupied.txt"))

        val outcome = RenameEngine.execute(steps)

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).cause is java.nio.file.FileAlreadyExistsException)
        assertTrue("plan rejected before touching disk", outcome.rolledBack)
        assertEquals(setOf("a.txt", "occupied.txt"), names(d))
        assertEquals("A", content(d, "a.txt"))
        assertEquals("other data", content(d, "occupied.txt"))
    }

    // ---- التراجع الداخلي: عائق يظهر وقت التنفيذ بعد اجتياز الفحص المسبق ----
    @Test
    fun failureMidRunRollsBackCompletedMoves() {
        val d = dir("rollback")
        write(d.resolve("a.txt"), "A")
        write(d.resolve("b.txt"), "B")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"))
        // بعد نقلة الخطوة 0 إلى المؤقت نحجز المؤقت القادم: فحص «المؤقت موجود»
        // في الخطوة 1 يرمي FileAlreadyExistsException فيبدأ التراجع الداخلي.
        val blocker: (RenamePhase, RenameStep) -> Unit = { phase, s ->
            if (phase == RenamePhase.TO_TEMP) {
                val i = steps.indexOfFirst { it.from == s.from }
                if (i >= 0 && i + 1 < steps.size) Files.writeString(steps[i + 1].temp, "blocked")
            }
        }
        val journal = RecordingJournal(hook = blocker)

        val outcome = RenameEngine.execute(steps, journal)

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        // المحرك يتراجع عمّا نقله هو فقط: الملفان الأصليان عادا والمؤقت الأول زال.
        // ملف الحاجب الأجنبي (صنعه الاختبار لا المحرك) يبقى كما هو.
        assertEquals(setOf("a.txt", "b.txt", steps[1].temp.fileName.toString()), names(d))
        assertFalse("المؤقت الذي نقله المحرك زال", Files.exists(steps[0].temp))
        assertEquals("A", content(d, "a.txt"))
        assertEquals("B", content(d, "b.txt"))
    }

    // ---- التبديل والسلسلة (A→B و B→A و A→B→C) ----
    @Test
    fun swapAndChainNamesSurviveTwoPhaseExecution() {
        val d = dir("swap")
        write(d.resolve("a.txt"), "A")
        write(d.resolve("b.txt"), "B")
        val swap = RenameEngine.plan(d, listOf("a.txt" to "b.txt", "b.txt" to "a.txt"))
        assertTrue(RenameEngine.execute(swap) is RenameOutcome.Success)
        assertEquals("A", content(d, "b.txt"))
        assertEquals("B", content(d, "a.txt"))

        val c = dir("chain")
        write(c.resolve("a.txt"), "A")
        write(c.resolve("b.txt"), "B")
        // لا c.txt مسبقاً: وجهة آخر حلقة خارج الدفعة تعارض مشروع يرفضه المحرك
        // (السلسلة الحقيقية = الوجهة الأخيرة غير موجودة بعد).
        val chain = RenameEngine.plan(c, listOf("a.txt" to "b.txt", "b.txt" to "c.txt"))
        assertTrue(RenameEngine.execute(chain) is RenameOutcome.Success)
        assertEquals("A", content(c, "b.txt"))
        assertEquals("B", content(c, "c.txt"))
        assertFalse(Files.exists(c.resolve("a.txt")))
    }

    // ---- الإيقاف عبر السجل: رامي داخل record يوقف التقدم ويبدأ التراجع ----
    @Test
    fun throwingJournalAbortsTheRunAndRollsBack() {
        val d = dir("cancel")
        write(d.resolve("a.txt"), "A")
        write(d.resolve("b.txt"), "B")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"))
        val journal = RecordingJournal(hook = { phase, _ ->
            if (phase == RenamePhase.TO_TEMP) throw IllegalStateException("cancelled by journal")
        })

        val outcome = RenameEngine.execute(steps, journal)

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals(setOf("a.txt", "b.txt"), names(d))
    }

    @Test
    fun batchRenamePlanFeedsEngineDirectly() {
        val d = dir("batch")
        write(d.resolve("IMG_1.jpg"), "one")
        write(d.resolve("IMG_2.jpg"), "two")
        val plan = BatchRename.plan(
            listOf(RenameInput("IMG_1.jpg"), RenameInput("IMG_2.jpg")),
            BatchRenameSpec(template = "holiday_##"), existingNames = listOf("IMG_1.jpg", "IMG_2.jpg")
        )
        assertTrue(plan.canApply)
        val steps = RenameEngine.plan(d, plan.renames())
        val outcome = RenameEngine.execute(steps)
        assertTrue(outcome is RenameOutcome.Success)
        assertEquals(setOf("holiday_1.jpg", "holiday_2.jpg"), names(d))
        assertEquals("one", content(d, "holiday_1.jpg"))
    }
}
