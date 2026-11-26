package org.openfilemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class RenameRaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(directory: Path, name: String, text: String) {
        Files.writeString(directory.resolve(name), text)
    }

    private fun contents(directory: Path): Map<String, String> =
        Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
                .associate { it.fileName.toString() to Files.readString(it) }
        }

    private fun names(directory: Path): Set<String> =
        Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }

    private fun assertNoTemps(directory: Path) {
        assertTrue(
            "temporary rename names leaked: ${names(directory)}",
            names(directory).none { it.startsWith(".fm-ren-") }
        )
    }

    @Test
    fun aDestinationThatAppearsDuringTheFirstFinalMoveIsNeverOverwritten() {
        val d = dir("first")
        write(d, "a.txt", "A")
        write(d, "b.txt", "B")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), "race1")

        var hookCalls = 0
        val outcome = RenameEngine.execute(steps, null) { step ->
            if (hookCalls++ == 0) Files.writeString(step.to, "intruder")
        }

        assertTrue("the appeared destination must be a conflict", outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        assertEquals("intruder", Files.readString(d.resolve("x.txt")))
        assertEquals(mapOf("a.txt" to "A", "b.txt" to "B", "x.txt" to "intruder"), contents(d))
        assertNoTemps(d)
    }

    @Test
    fun aDestinationThatAppearsDuringALaterFinalMoveRollsBackEarlierSteps() {
        val d = dir("later")
        write(d, "a.txt", "A")
        write(d, "b.txt", "B")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "x.txt", "b.txt" to "y.txt"), "race2")

        var hookCalls = 0
        val outcome = RenameEngine.execute(steps, null) { step ->
            if (hookCalls++ == 1) Files.writeString(step.to, "intruder")
        }

        assertTrue("the appeared destination must be a conflict", outcome is RenameOutcome.Failure)
        assertTrue((outcome as RenameOutcome.Failure).rolledBack)
        // The first step had already reached its final name; rollback must
        // restore it through the temporary name, not leave x.txt behind.
        assertEquals("intruder", Files.readString(d.resolve("y.txt")))
        assertEquals(mapOf("a.txt" to "A", "b.txt" to "B", "y.txt" to "intruder"), contents(d))
        assertTrue("the first step must be rolled back", !Files.exists(d.resolve("x.txt")))
        assertNoTemps(d)
    }

    /**
     * عقد الإنتاج (منفذ من variant A): بلا خطاف حقن، التنفيذ العام نفسه يفشل
     * على وجهة محجوزة قبل الدفعة بلا أي استبدال — فحص الوجهة تسريع فقط
     * والنشر دائماً no-replace.
     */
    @Test
    fun productionPathWithoutHookNeverReplacesAnOccupiedDestination() {
        val d = dir("plain")
        write(d, "a.txt", "A")
        write(d, "y.txt", "existing target")
        val steps = RenameEngine.plan(d, listOf("a.txt" to "y.txt"))

        val outcome = RenameEngine.execute(steps)

        assertTrue(outcome is RenameOutcome.Failure)
        assertEquals("existing target", Files.readString(d.resolve("y.txt")))
        assertEquals("A", Files.readString(d.resolve("a.txt")))
        assertNoTemps(d)
    }
}
