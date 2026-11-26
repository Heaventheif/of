package org.openfilemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RenameEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(directory: Path, name: String, text: String) {
        Files.writeString(directory.resolve(name), text)
    }

    private fun names(directory: Path): Set<String> =
        Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }

    private fun contents(directory: Path): Map<String, String> =
        Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
                .associate { it.fileName.toString() to Files.readString(it) }
        }

    private fun assertNoTemps(directory: Path) {
        assertTrue(
            "temporary rename names leaked: ${names(directory)}",
            names(directory).none { it.startsWith(".fm-ren-") }
        )
    }

    @Test
    fun renamesEveryFileThroughTemporaryNames() {
        val d = dir("ok")
        write(d, "a.txt", "A")
        write(d, "b.txt", "B")

        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), "ok1")
        val outcome = RenameEngine.execute(steps)

        assertTrue(outcome is RenameOutcome.Success)
        assertEquals(setOf("x.txt", "y.txt"), names(d))
        assertEquals(mapOf("x.txt" to "A", "y.txt" to "B"), contents(d))
        assertNoTemps(d)
    }

    @Test
    fun swapAndCycleNamesWorkThroughTemporaryNames() {
        val swap = dir("swap")
        write(swap, "a.txt", "A")
        write(swap, "b.txt", "B")
        val swapOutcome = RenameEngine.execute(
            RenameEngine.plan(swap, listOf("a.txt" to "b.txt", "b.txt" to "a.txt"), "swap1")
        )
        assertTrue(swapOutcome is RenameOutcome.Success)
        assertEquals(mapOf("a.txt" to "B", "b.txt" to "A"), contents(swap))
        assertNoTemps(swap)

        val cycle = dir("cycle")
        write(cycle, "a.txt", "A")
        write(cycle, "b.txt", "B")
        write(cycle, "c.txt", "C")
        val cycleOutcome = RenameEngine.execute(
            RenameEngine.plan(
                cycle,
                listOf("a.txt" to "b.txt", "b.txt" to "c.txt", "c.txt" to "a.txt"),
                "cycle1"
            )
        )
        assertTrue(cycleOutcome is RenameOutcome.Success)
        assertEquals(mapOf("a.txt" to "C", "b.txt" to "A", "c.txt" to "B"), contents(cycle))
        assertNoTemps(cycle)
    }

    @Test
    fun occupiedTargetOutsideTheBatchIsRejectedBeforeAnyMove() {
        val d = dir("collision")
        write(d, "a.txt", "A")
        write(d, "target.txt", "T")

        val outcome = RenameEngine.execute(
            RenameEngine.plan(d, listOf("a.txt" to "target.txt"), "collision1")
        )

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals(mapOf("a.txt" to "A", "target.txt" to "T"), contents(d))
        assertNoTemps(d)
    }

    @Test
    fun failureDuringTheFinalPhaseRollsBackEveryMove() {
        val d = dir("rollback")
        write(d, "a.txt", "A")
        write(d, "b.txt", "B")

        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), "rollback1")
        val failingJournal = RenameJournal { phase, _ ->
            if (phase == RenamePhase.TO_FINAL) throw IOException("simulated final-phase failure")
        }
        val outcome = RenameEngine.execute(steps, failingJournal)

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals(mapOf("a.txt" to "A", "b.txt" to "B"), contents(d))
        assertNoTemps(d)
    }

    @Test
    fun missingSourceIsRejectedWithoutTouchingTheFolder() {
        val d = dir("missing")
        write(d, "a.txt", "A")

        val outcome = RenameEngine.execute(RenameEngine.plan(d, listOf("ghost.txt" to "x.txt"), "missing1"))

        assertTrue(outcome is RenameOutcome.Failure)
        assertFalse(Files.exists(d.resolve("x.txt")))
        assertEquals(mapOf("a.txt" to "A"), contents(d))
        assertNoTemps(d)
    }

    @Test
    fun unchangedPairsProduceNoSteps() {
        val d = dir("unchanged")
        write(d, "a.txt", "A")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "a.txt"), "same1")
        assertTrue(steps.isEmpty())
        assertTrue(RenameEngine.execute(steps) is RenameOutcome.Success)
        assertEquals(mapOf("a.txt" to "A"), contents(d))
    }

    @Test
    fun journalRecordsBothPhases() {
        val d = dir("journal")
        write(d, "a.txt", "A")
        val phases = ArrayList<RenamePhase>()
        val outcome = RenameEngine.execute(
            RenameEngine.plan(d, listOf("a.txt" to "b.txt"), "journal1")
        ) { phase, _ -> phases += phase }

        assertTrue(outcome is RenameOutcome.Success)
        assertEquals(listOf(RenamePhase.TO_TEMP, RenamePhase.TO_FINAL), phases)
        assertEquals(setOf("b.txt"), names(d))
    }

    @Test
    fun failureDuringTheTemporaryPhaseRollsBack() {
        val d = dir("rollback-temp")
        write(d, "a.txt", "A")
        write(d, "b.txt", "B")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), "rollback2")
        var calls = 0
        val failingJournal = RenameJournal { _, _ ->
            calls++
            if (calls == 2) throw IOException("simulated temp-phase failure")
        }
        val outcome = RenameEngine.execute(steps, failingJournal)

        assertTrue(outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals(mapOf("a.txt" to "A", "b.txt" to "B"), contents(d))
        assertNoTemps(d)
    }

    @Test
    fun duplicateTemporaryPathIsRejected() {
        val d = dir("dup-temp")
        write(d, "a.txt", "A")
        val a = d.resolve("a.txt")
        val temp = d.resolve(".fm-ren-manual-0")
        val steps = listOf(
            RenameStep(a, temp, d.resolve("x.txt")),
            RenameStep(a, temp, d.resolve("y.txt"))
        )
        try {
            val outcome = RenameEngine.execute(steps)
            assertTrue(outcome is RenameOutcome.Failure)
        } catch (e: Exception) {
            // validatePlan is allowed to reject eagerly; either shape is a failure.
            assertTrue(e is IOException)
        }
        assertNoTemps(d)
    }
}
