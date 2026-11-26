package org.openfilemanager.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FileOpEngineProgressTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    @Test
    fun twoFileCopyEmitsExactProgressEvents() = runBlocking {
        val src = dir("p1-src")
        val dst = dir("p1-dst")
        write(src.resolve("a.txt"), "AAA") // 3 بايتات
        write(src.resolve("b.txt"), "BBBBB") // 5 بايتات
        val events = mutableListOf<FileOpProgress>()

        val engine = FileOpEngine(MemoryOpJournal(), onProgress = { events += it })
        engine.copy(listOf(src.resolve("a.txt"), src.resolve("b.txt")), dst, ConflictPolicy.SKIP)

        // ملف صغير = دفعة واحدة = حدث واحد لكل ملف؛ bytesTotal مجموع الأحجام المعدّة.
        assertEquals(
            listOf(
                FileOpProgress(itemIndex = 0, itemCount = 2, bytesDone = 3, bytesTotal = 8, current = "a.txt"),
                FileOpProgress(itemIndex = 1, itemCount = 2, bytesDone = 8, bytesTotal = 8, current = "b.txt")
            ),
            events
        )
    }

    @Test
    fun journalItemStatesObservedAtProgressTime() = runBlocking {
        val src = dir("p2-src")
        val dst = dir("p2-dst")
        write(src.resolve("a.txt"), "AAA")
        write(src.resolve("b.txt"), "BBB")
        val snapshots = mutableListOf<List<ItemState>>()
        val journal = MemoryOpJournal()

        val engine = FileOpEngine(journal, onProgress = { snapshots += journal.items("opprog2").map { it.state } })
        engine.copy(listOf(src.resolve("a.txt"), src.resolve("b.txt")), dst, ConflictPolicy.SKIP, opId = "opprog2")

        // لحظة نسخ العنصر الأول: هو RUNNING والثاني ما يزال PENDING في السجل؛
        // لحظة الثاني: الأول DONE والثاني RUNNING.
        assertEquals(
            listOf(
                listOf(ItemState.RUNNING, ItemState.PENDING),
                listOf(ItemState.DONE, ItemState.RUNNING)
            ),
            snapshots
        )
        // بعد الاكتمال: الكل DONE.
        assertTrue(journal.items("opprog2").all { it.state == ItemState.DONE })
    }

    @Test
    fun sameVolumeMoveReportsCountBasedProgressWithZeroBytes() = runBlocking {
        val src = dir("p3-src")
        val dst = dir("p3-dst")
        write(src.resolve("d/f.txt"), "F")
        val events = mutableListOf<FileOpProgress>()

        val engine = FileOpEngine(MemoryOpJournal(), onProgress = { events += it })
        engine.move(listOf(src.resolve("d")), dst, ConflictPolicy.SKIP)

        // النقل داخل الوحدة إعادة تسمية O(1): الحدث الوحيد ببايتات صفرية.
        assertEquals(listOf(FileOpProgress(itemIndex = 0, itemCount = 1, bytesDone = 0, bytesTotal = 0, current = "d")), events)
    }

    @Test
    fun deleteReportsSingleProgressEventPerTree() = runBlocking {
        val d = dir("p4")
        write(d.resolve("a.txt"), "A")
        val events = mutableListOf<FileOpProgress>()

        val engine = FileOpEngine(MemoryOpJournal(), onProgress = { events += it })
        engine.delete(listOf(d.resolve("a.txt")))

        // الحذف بلا حجم (عنصر TREE_DELETE بحجم 0): حدث واحد قبل التنفيذ.
        assertEquals(listOf(FileOpProgress(itemIndex = 0, itemCount = 1, bytesDone = 0, bytesTotal = 0, current = "a.txt")), events)
    }
}
