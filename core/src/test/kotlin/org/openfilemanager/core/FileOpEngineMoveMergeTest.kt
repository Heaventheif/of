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

class FileOpEngineMoveMergeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    // ---------- نقل داخل الوحدة ----------

    @Test
    fun moveFileSameVolumeRenamesAndUpdatesJournal() = runBlocking {
        val src = dir("mf-src")
        val dst = dir("mf-dst")
        write(src.resolve("a.txt"), "A")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).move(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertEquals("A", Files.readString(dst.resolve("a.txt")))
        assertFalse("المصدر يزول بالنقل", Files.exists(src.resolve("a.txt")))

        val item = journal.items(journal.ops().single().id).single()
        assertEquals(OpItemKind.TREE_MOVE, item.itemKind)
        assertEquals(ItemState.DONE, item.state)
        assertEquals("السجل يحدّث الوجهة الفعلية", dst.resolve("a.txt").toString(), item.dst)
    }

    @Test
    fun moveTreeSameVolumeIsSingleRenameWithLazyBytes() = runBlocking {
        val src = dir("mt-src")
        val dst = dir("mt-dst")
        write(src.resolve("tree/sub/f.txt"), "F")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).move(listOf(src.resolve("tree")), dst, ConflictPolicy.SKIP)

        assertEquals("الشجرة كلها عنصر واحد بإعادة تسمية", FileOpStats(1, 0, 0), stats)
        assertEquals("F", Files.readString(dst.resolve("tree/sub/f.txt")))
        assertFalse(Files.exists(src.resolve("tree")))

        val record = journal.op(journal.ops().single().id)!!
        // الحجم كسول: نقل داخل الوحدة O(1) — البايتات 0 والتقدم بالعدّ لا بالحجم.
        assertEquals(0L, record.totalBytes)
    }

    @Test
    fun moveOntoExistingFileSkipKeepsBoth() = runBlocking {
        val src = dir("ms-src")
        val dst = dir("ms-dst")
        write(src.resolve("a.txt"), "new")
        write(dst.resolve("a.txt"), "old")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).move(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(0, 0, 1), stats)
        assertEquals("old", Files.readString(dst.resolve("a.txt")))
        assertEquals("المصدر يبقى عند التخطي", "new", Files.readString(src.resolve("a.txt")))
        assertEquals(CoreOpCode.DESTINATION_EXISTS, journal.items(journal.ops().single().id).single().error)
    }

    @Test
    fun moveOntoExistingFileReplaceReplacesContent() = runBlocking {
        val src = dir("mr-src")
        val dst = dir("mr-dst")
        write(src.resolve("a.txt"), "new")
        write(dst.resolve("a.txt"), "old")

        val stats = FileOpEngine(MemoryOpJournal()).move(listOf(src.resolve("a.txt")), dst, ConflictPolicy.REPLACE)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertEquals("new", Files.readString(dst.resolve("a.txt")))
        val leftovers = Files.list(dst).use { s -> s.anyMatch { it.fileName.toString().startsWith(".fm-replaced-") } }
        assertFalse("نسخة الاستبدال الاحتياطية تُمسح بعد النجاح", leftovers)
    }

    @Test
    fun moveDirIntoExistingDirKeepBothRenamesWholeTree() = runBlocking {
        val src = dir("kb-src")
        val dst = dir("kb-dst")
        write(src.resolve("d/a.txt"), "A")
        write(dst.resolve("d/c.txt"), "C")

        // KEEP_BOTH على مجلد فوق مجلد: إعادة تسمية ذرية للشجرة كلها باسم بديل، لا دمج.
        val stats = FileOpEngine(MemoryOpJournal()).move(listOf(src.resolve("d")), dst, ConflictPolicy.KEEP_BOTH)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertEquals("A", Files.readString(dst.resolve("d (1)/a.txt")))
        assertEquals("وجهة KEEP_BOTH لم تُمس", "C", Files.readString(dst.resolve("d/c.txt")))
        assertFalse(Files.exists(src.resolve("d")))
    }

    @Test
    fun moveSourceSameAsDestinationCountsSkipped() = runBlocking {
        val parent = dir("self")
        write(parent.resolve("d/a.txt"), "A")
        val journal = MemoryOpJournal()

        // نقل مجلد إلى أبيه: الوجهة المحسوبة هي المصدر نفسه → تخطٍ.
        val stats = FileOpEngine(journal).move(listOf(parent.resolve("d")), parent, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(0, 0, 1), stats)
        val item = journal.items(journal.ops().single().id).single()
        assertEquals(ItemState.SKIPPED, item.state)
        assertEquals(CoreOpCode.SAME_PATH, item.error)
        assertEquals("A", Files.readString(parent.resolve("d/a.txt")))
    }

    @Test
    fun moveFileOntoExistingDirReplaceRefuses() = runBlocking {
        val src = dir("ref-src")
        val dst = dir("ref-dst")
        write(src.resolve("a.txt"), "A")
        write(dst.resolve("a.txt/keep.txt"), "K")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).move(listOf(src.resolve("a.txt")), dst, ConflictPolicy.REPLACE)

        assertEquals("المجلد ليس ملفاً قابلاً للاستبدال: رفض لا محو شجرة", FileOpStats(0, 1, 0), stats)
        val item = journal.items(journal.ops().single().id).single()
        assertEquals(ItemState.FAILED, item.state)
        assertTrue(item.error.contains("refusing to replace a directory with a file"))
        assertEquals("A", Files.readString(src.resolve("a.txt")))
        assertEquals("K", Files.readString(dst.resolve("a.txt/keep.txt")))
    }

    @Test
    fun moveTargetInsideSourceIsRejected() = runBlocking {
        val root = dir("mguard")
        val src = Files.createDirectory(root.resolve("src"))
        write(src.resolve("f.txt"), "F")

        try {
            runBlocking { FileOpEngine(MemoryOpJournal()).move(listOf(src), src.resolve("nested"), ConflictPolicy.REPLACE) }
            fail()
        } catch (e: TargetInsideSourceException) {
            assertTrue(e.message!!.contains(CoreOpCode.DEST_INSIDE_SOURCE))
        }
        assertFalse("لا مجلد متروك داخل المصدر", Files.exists(src.resolve("nested")))
        assertTrue(Files.exists(src.resolve("f.txt")))
    }

    // ---------- دمج النقل: مجلد فوق مجلد بسياسة REPLACE ----------

    @Test
    fun mergeMoveReplacesConflictingFilesAndKeepsDestOnlyFiles() = runBlocking {
        val src = dir("mg-src")
        val dst = dir("mg-dst")
        write(src.resolve("d/a.txt"), "A")
        write(src.resolve("d/sub/b.txt"), "B")
        write(dst.resolve("d/a.txt"), "OLD")
        write(dst.resolve("d/c.txt"), "C")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).move(listOf(src.resolve("d")), dst, ConflictPolicy.REPLACE)

        assertEquals("الجذر + مجلد فرعي + ملفان (عدّ لكل عنصر)", FileOpStats(4, 0, 0), stats)
        assertEquals("الملف المتعارض استُبدل", "A", Files.readString(dst.resolve("d/a.txt")))
        assertEquals("ملف الوجهة غير المتعارض بقي", "C", Files.readString(dst.resolve("d/c.txt")))
        assertEquals("B", Files.readString(dst.resolve("d/sub/b.txt")))
        assertFalse("المصدر أُفرغ وحُذف", Files.exists(src.resolve("d")))

        val items = journal.items(journal.ops().single().id)
        assertEquals("جذر TREE_MOVE + نقاط تفتيش الدمج المسجلة", 5, items.size)
        val root = items.first { it.itemKind == OpItemKind.TREE_MOVE }
        assertEquals(ItemState.DONE, root.state)
        assertEquals(dst.resolve("d").toString(), root.dst)
        // نقاط تفتيش الدمج تُلحق بفهارس من النطاق الأعلى (MERGE_SUB_INDEX_BASE).
        val mergeIndexBase = Int.MAX_VALUE / 2
        assertEquals(4, items.count { it.index >= mergeIndexBase })
        assertTrue(items.filter { it.index >= mergeIndexBase }.all { it.state == ItemState.DONE })
    }

    @Test
    fun mergeMoveViaAskResolverConsultedOnceForRoot() = runBlocking {
        val src = dir("mga-src")
        val dst = dir("mga-dst")
        write(src.resolve("d/a.txt"), "A")
        write(dst.resolve("d/a.txt"), "OLD")
        write(dst.resolve("d/c.txt"), "C")
        var calls = 0
        val requests = ArrayList<ConflictRequest>()
        val engine = FileOpEngine(
            MemoryOpJournal(),
            resolveConflict = { req ->
                calls++; requests += req
                ConflictDecision(ConflictPolicy.REPLACE, applyToAll = true)
            }
        )

        val stats = engine.move(listOf(src.resolve("d")), dst, ConflictPolicy.ASK)

        assertEquals("قرار واحد على جذر المجموعة؛ ملفات الدمج تُستبدل دون سؤال", 1, calls)
        assertEquals(src.resolve("d"), requests.single().source)
        assertEquals(dst.resolve("d"), requests.single().target)
        assertTrue(requests.single().isDirectory)
        assertEquals("الجذر + الملف الوحيد (عدّ لكل عنصر)", FileOpStats(2, 0, 0), stats)
        assertEquals("A", Files.readString(dst.resolve("d/a.txt")))
        assertEquals("C", Files.readString(dst.resolve("d/c.txt")))
    }

    @Test
    fun mergeMoveReplacesDestFileThatBlocksSubdirectory() = runBlocking {
        val src = dir("mgb-src")
        val dst = dir("mgb-dst")
        write(src.resolve("d/sub/x.txt"), "X")
        // مسار المجلد الفرعي في الوجهة ملف معترض.
        write(dst.resolve("d/sub"), "blocking")

        val stats = FileOpEngine(MemoryOpJournal()).move(listOf(src.resolve("d")), dst, ConflictPolicy.REPLACE)

        assertEquals(FileOpStats(3, 0, 0), stats)
        assertEquals("الملف المعترض أُزاح واستُبدل بالمجلد", "X", Files.readString(dst.resolve("d/sub/x.txt")))
        assertFalse(Files.exists(src.resolve("d")))
    }

    // ---------- نقل بين وحدتين (نسخ بهاش ثم حذف المصدر) ----------

    @Test
    fun crossVolumeMoveFileCopiesThenDeletesSource() = runBlocking {
        val src = dir("cvf-src")
        val dst = dir("cvf-dst")
        write(src.resolve("a.txt"), "A")
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(journal, sameVolume = { _, _ -> false })

        val stats = engine.move(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertEquals("A", Files.readString(dst.resolve("a.txt")))
        assertFalse("المصدر يُحذف بعد تطابق الهاش", Files.exists(src.resolve("a.txt")))
        // بين وحدتين الملف عنصر FILE (لا TREE_MOVE الذرية).
        assertEquals(OpItemKind.FILE, journal.items(journal.ops().single().id).single().itemKind)
    }

    @Test
    fun crossVolumeMoveDirMovesWholeTreeAndRemovesSource() = runBlocking {
        val src = dir("cvd-src")
        val dst = dir("cvd-dst")
        write(src.resolve("d/a.txt"), "A")
        write(src.resolve("d/sub/b.txt"), "B")
        val engine = FileOpEngine(MemoryOpJournal(), sameVolume = { _, _ -> false })

        val stats = engine.move(listOf(src.resolve("d")), dst, ConflictPolicy.SKIP)

        assertEquals("مجلدان + ملفان", FileOpStats(4, 0, 0), stats)
        assertEquals("A", Files.readString(dst.resolve("d/a.txt")))
        assertEquals("B", Files.readString(dst.resolve("d/sub/b.txt")))
        assertFalse("الهيكل المجلدي للمصدر يُحذف كله بعد الإفراغ", Files.exists(src.resolve("d")))
    }
}
