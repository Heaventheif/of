package org.openfilemanager.core
import org.openfilemanager.core.CoreOpCode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FileOpEngineDeleteTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    @Test
    fun deleteFileRemovesItAndRecordsEmptyTargetDir() = runBlocking {
        val d = dir("del1")
        write(d.resolve("a.txt"), "A")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).delete(listOf(d.resolve("a.txt")), opId = "opdel1")

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertFalse(Files.exists(d.resolve("a.txt")))

        val record = journal.op("opdel1")!!
        assertEquals(OpState.FINISHED, record.state)
        assertEquals(OpKind.DELETE, record.kind)
        // الحذف بلا مجلد وجهة: السجل يخزن سلسلة فارغة.
        assertEquals("", record.targetDir)
        // core الآن يُرجع رمزاً لا نصاً عربياً في السجل.
        assertTrue(record.message!!.startsWith(CoreOpCode.OP_SUMMARY))
        assertTrue(record.message!!.contains(CoreOpCode.KIND_DELETE))

        val item = journal.items("opdel1").single()
        assertEquals(OpItemKind.TREE_DELETE, item.itemKind)
        assertEquals(ItemState.DONE, item.state)
        assertEquals(d.resolve("a.txt").toString(), item.src)
        assertEquals("", item.dst)
    }

    @Test
    fun deleteTreeRemovesNestedContentAsOneItem() = runBlocking {
        val d = dir("del2")
        write(d.resolve("tree/a.txt"), "A")
        write(d.resolve("tree/sub/b.txt"), "B")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).delete(listOf(d.resolve("tree")))

        assertEquals("الشجرة كلها عنصر واحد ناجح", FileOpStats(1, 0, 0), stats)
        assertFalse(Files.exists(d.resolve("tree")))
        assertEquals(1, journal.items(journal.ops().single().id).size)
    }

    @Test
    fun deleteMissingSourceCountsOk() = runBlocking {
        val d = dir("del3")
        val ghost = d.resolve("ghost.txt") // لم يوجد أصلاً
        val journal = MemoryOpJournal()

        // سلوك حالي: deleteTree يتسامح مع غياب الجذر فيُعدّ العنصر ناجحاً.
        val stats = FileOpEngine(journal).delete(listOf(ghost))

        assertEquals(FileOpStats(1, 0, 0), stats)
        val record = journal.op(journal.ops().single().id)!!
        assertEquals(OpState.FINISHED, record.state)
        assertEquals(1, record.ok)
        assertEquals(0, record.failed)
    }

    @Test
    fun deleteMultipleSourcesCountsEachTreeOnce() = runBlocking {
        val d = dir("del4")
        write(d.resolve("f1.txt"), "1")
        write(d.resolve("f2.txt"), "2")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).delete(listOf(d.resolve("f1.txt"), d.resolve("f2.txt")))

        assertEquals(FileOpStats(2, 0, 0), stats)
        assertFalse(Files.exists(d.resolve("f1.txt")))
        assertFalse(Files.exists(d.resolve("f2.txt")))
        assertEquals(2, journal.items(journal.ops().single().id).size)
    }

    @Test
    fun deleteSymlinkRemovesLinkOnly() = runBlocking {
        val d = dir("del5")
        val real = write(d.resolve("real.txt"), "R")
        val link = d.resolve("link")
        Files.createSymbolicLink(link, real)

        val stats = FileOpEngine(MemoryOpJournal()).delete(listOf(link))

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertFalse("الرابط نفسه يُحذف", Files.exists(link))
        assertTrue("هدف الرابط لا يُمس", Files.exists(real))
    }
}
