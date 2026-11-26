package org.openfilemanager.core
import org.openfilemanager.core.CoreOpCode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FileOpEngineCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    // ---------- النسخ البسيط والشجرة ----------

    @Test
    fun simpleFileCopyPublishesContentAndJournalState() = runBlocking {
        val src = dir("src")
        val dst = dir("dst")
        write(src.resolve("a.txt"), "AAA")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).copy(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP, opId = "opcopy1")

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertEquals("AAA", Files.readString(dst.resolve("a.txt")))
        assertTrue("النسخ لا يحذف المصدر", Files.exists(src.resolve("a.txt")))

        // حالة السجل النهائية لعملية ناجحة (منسّق السجل المستقبلي).
        val record = journal.op("opcopy1")!!
        assertEquals(OpState.FINISHED, record.state)
        assertEquals(OpKind.COPY, record.kind)
        assertEquals(1, record.ok)
        assertEquals(0, record.failed)
        assertEquals(0, record.skipped)
        // core الآن يُرجع رمزاً لا نصاً عربياً في السجل.
        assertTrue(record.message!!.startsWith(CoreOpCode.OP_SUMMARY))
        assertTrue(record.message!!.contains(CoreOpCode.KIND_COPY))
        assertEquals(dst.toString(), record.targetDir)
        assertEquals(listOf(src.resolve("a.txt").toString()), record.sources)
        assertEquals(3L, record.totalBytes)

        val items = journal.items("opcopy1")
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(OpItemKind.FILE, item.itemKind)
        assertEquals(ItemState.DONE, item.state)
        assertEquals(src.resolve("a.txt").toString(), item.src)
        assertEquals(dst.resolve("a.txt").toString(), item.dst)
        assertEquals("", item.error)
    }

    @Test
    fun nestedTreeCopyPreservesRelativePathsAndCountsEveryEntry() = runBlocking {
        val src = dir("tree-src")
        val dst = dir("tree-dst")
        write(src.resolve("top.txt"), "T")
        write(src.resolve("sub/inner.txt"), "I")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).copy(listOf(src), dst, ConflictPolicy.SKIP)

        // الجذر + مجلد فرعي + ملفان: كل مُدخل مجلد يُعدّ ok منفصلاً.
        assertEquals(FileOpStats(4, 0, 0), stats)
        assertEquals("T", Files.readString(dst.resolve("tree-src/top.txt")))
        assertEquals("I", Files.readString(dst.resolve("tree-src/sub/inner.txt")))

        val items = journal.items(journal.ops().single().id)
        assertEquals(4, items.size)
        assertEquals(2, items.count { it.itemKind == OpItemKind.DIR_CREATE })
        assertEquals(2, items.count { it.itemKind == OpItemKind.FILE })
        assertTrue(items.all { it.state == ItemState.DONE })
        assertTrue(
            "المسارات النسبية داخل الشجرة تُحفظ",
            items.any { it.dst == dst.resolve("tree-src/sub/inner.txt").toString() }
        )
    }

    @Test
    fun copyIntoExistingDirectoryMergesAndSkipsRootCounting() = runBlocking {
        val src = dir("merge-src")
        write(src.resolve("d/a.txt"), "A")
        val dst = dir("merge-dst")
        write(dst.resolve("d/c.txt"), "C")

        // سياسة KEEP_BOTH لا تُستشار لمجلد جذر موجود أصلاً كمجلد: الدمج هو السلوك.
        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(src.resolve("d")), dst, ConflictPolicy.KEEP_BOTH)

        assertEquals("الجذر الموجود لا يُعدّ ok، الملفات فقط", FileOpStats(1, 0, 0), stats)
        assertEquals("A", Files.readString(dst.resolve("d/a.txt")))
        assertEquals("محتوى الوجهة غير المتعارض يبقى", "C", Files.readString(dst.resolve("d/c.txt")))
    }

    @Test
    fun copyEmptyDirCountsJustTheRoot() = runBlocking {
        val src = dir("empty-src")
        Files.createDirectories(src.resolve("hollow"))
        val dst = dir("empty-dst")

        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(src.resolve("hollow")), dst, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertTrue(Files.isDirectory(dst.resolve("hollow")))
    }

    @Test
    fun copyDirIntoItsOwnParentGetsAlternativeName() = runBlocking {
        val parent = dir("parent")
        write(parent.resolve("d/a.txt"), "A")

        // نسخ مجلد داخل أبيه نفسه: الوجهة كانت ستصبح المصدر نفسه، فتُوزع باسم «d (1)».
        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(parent.resolve("d")), parent, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(2, 0, 0), stats)
        assertEquals("A", Files.readString(parent.resolve("d (1)/a.txt")))
        // core الآن يُرجع رمزاً لا نصاً عربياً.
        assertEquals("الأصل يبقى", "A", Files.readString(parent.resolve("d/a.txt")))
    }

    // ---------- سياسات التعارض على تصادم اسم ملف ----------

    @Test
    fun replacePolicyOverwritesCollisionContent() = runBlocking {
        val src = dir("rep-src")
        val dst = dir("rep-dst")
        write(src.resolve("a.txt"), "new")
        write(dst.resolve("a.txt"), "old")

        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(src.resolve("a.txt")), dst, ConflictPolicy.REPLACE)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertEquals("new", Files.readString(dst.resolve("a.txt")))
    }

    @Test
    fun skipPolicyKeepsDestinationAndCountsSkipped() = runBlocking {
        val src = dir("skip-src")
        val dst = dir("skip-dst")
        write(src.resolve("a.txt"), "new")
        write(dst.resolve("a.txt"), "old")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).copy(listOf(src.resolve("a.txt")), dst, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(0, 0, 1), stats)
        assertEquals("old", Files.readString(dst.resolve("a.txt")))
        val item = journal.items(journal.ops().single().id).single()
        assertEquals(ItemState.SKIPPED, item.state)
        assertEquals(CoreOpCode.DESTINATION_EXISTS, item.error)
    }

    @Test
    fun keepBothAutoRenameFormatVariants() = runBlocking {
        // الصيغة الفعلية: «الجذع (ن).الامتداد» — آخر نقطة فقط هي الامتداد،
        // والاسم الذي يبدأ بنقطة (.gitignore) يُعامَل بلا امتداد.
        data class Case(val existing: String, val expected: String)

        val cases = listOf(
            Case("report.pdf", "report (1).pdf"),
            Case("archive.tar.gz", "archive.tar (1).gz"),
            Case("README", "README (1)"),
            Case(".gitignore", ".gitignore (1)"),
            Case("report.pdf", "report (2).pdf") // مع وجود «report (1).pdf» سلفاً
        )
        cases.forEachIndexed { i, (existing, expected) ->
            val src = dir("kb-src$i")
            val dst = dir("kb-dst$i")
            write(src.resolve(existing), "new")
            write(dst.resolve(existing), "old")
            if (i == 4) write(dst.resolve("report (1).pdf"), "one")

            val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(src.resolve(existing)), dst, ConflictPolicy.KEEP_BOTH)

            assertEquals(FileOpStats(1, 0, 0), stats)
            assertEquals("القديم يبقى بمكانه", "old", Files.readString(dst.resolve(existing)))
            assertEquals("new", Files.readString(dst.resolve(expected)))
        }
    }

    @Test
    fun twoSourcesWithSameNameGetSequentialAlternativeNames() = runBlocking {
        val f1 = dir("dup1")
        val f2 = dir("dup2")
        val dst = dir("dup-dst")
        write(f1.resolve("same.txt"), "one")
        write(f2.resolve("same.txt"), "two")

        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(f1.resolve("same.txt"), f2.resolve("same.txt")), dst, ConflictPolicy.KEEP_BOTH)

        assertEquals(FileOpStats(2, 0, 0), stats)
        assertEquals("one", Files.readString(dst.resolve("same.txt")))
        assertEquals("two", Files.readString(dst.resolve("same (1).txt")))
    }

    @Test
    fun askPolicyResolverInvokedOnceWithApplyToAll() = runBlocking {
        val src = dir("ask-src")
        val dst = dir("ask-dst")
        write(src.resolve("x.txt"), "x-new")
        write(src.resolve("y.txt"), "y-new")
        write(dst.resolve("x.txt"), "x-old")
        write(dst.resolve("y.txt"), "y-old")
        var calls = 0
        val engine = FileOpEngine(
            MemoryOpJournal(),
            resolveConflict = { calls++; ConflictDecision(ConflictPolicy.KEEP_BOTH, applyToAll = true) }
        )

        val stats = engine.copy(listOf(src.resolve("x.txt"), src.resolve("y.txt")), dst, ConflictPolicy.ASK)

        assertEquals(FileOpStats(2, 0, 0), stats)
        assertEquals("applyToAll يخزّن القرار: حوار واحد لكل الدفعة", 1, calls)
        assertEquals("x-new", Files.readString(dst.resolve("x (1).txt")))
        assertEquals("y-new", Files.readString(dst.resolve("y (1).txt")))
    }

    @Test
    fun askPolicyWithoutApplyToAllAsksForEachConflict() = runBlocking {
        val src = dir("ask2-src")
        val dst = dir("ask2-dst")
        write(src.resolve("x.txt"), "x-new")
        write(src.resolve("y.txt"), "y-new")
        write(dst.resolve("x.txt"), "x-old")
        write(dst.resolve("y.txt"), "y-old")
        var calls = 0
        val engine = FileOpEngine(
            MemoryOpJournal(),
            resolveConflict = { calls++; ConflictDecision(ConflictPolicy.KEEP_BOTH, applyToAll = false) }
        )

        val stats = engine.copy(listOf(src.resolve("x.txt"), src.resolve("y.txt")), dst, ConflictPolicy.ASK)

        assertEquals(FileOpStats(2, 0, 0), stats)
        assertEquals("بلا applyToAll: قرار لكل تعارض", 2, calls)
    }

    @Test
    fun askPolicyDefaultResolverSkipsAllConflicts() = runBlocking {
        val src = dir("ask3-src")
        val dst = dir("ask3-dst")
        write(src.resolve("x.txt"), "x-new")
        write(src.resolve("y.txt"), "y-new")
        write(dst.resolve("x.txt"), "x-old")
        write(dst.resolve("y.txt"), "y-old")

        // المحرك الافتراضي بلا حلّال محقون: SKIP مع applyToAll.
        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(src.resolve("x.txt"), src.resolve("y.txt")), dst, ConflictPolicy.ASK)

        assertEquals(FileOpStats(0, 0, 2), stats)
        assertEquals("x-old", Files.readString(dst.resolve("x.txt")))
        assertEquals("y-old", Files.readString(dst.resolve("y.txt")))
    }

    // ---------- أشكال الأخطاء وحواجز المسار ----------

    @Test
    fun copyTargetInsideSourceIsRejectedAndCreatesNothing() = runBlocking {
        val root = dir("guard")
        val src = Files.createDirectory(root.resolve("src"))
        write(src.resolve("f.txt"), "F")
        val journal = MemoryOpJournal()
        val engine = FileOpEngine(journal)

        // الوجهة داخل الشجرة نفسها (مجلد غير موجود بعد).
        try {
            engine.copy(listOf(src), src.resolve("nested"), ConflictPolicy.REPLACE)
            fail()
        } catch (e: TargetInsideSourceException) {
            assertTrue(e.message!!.contains(CoreOpCode.DEST_INSIDE_SOURCE))
        }
        assertFalse("الفحص قبل الإنشاء: لا مجلد فارغ متروك داخل المصدر", Files.exists(src.resolve("nested")))

        // الوجهة هي المصدر نفسه.
        try {
            engine.copy(listOf(src), src, ConflictPolicy.REPLACE)
            fail()
        } catch (_: TargetInsideSourceException) {
        }

        // الرفض قبل startOp: لا سجل عملية أصلاً.
        assertTrue(journal.ops().isEmpty())
    }

    @Test
    fun copyMissingSourceCountsFailureAndFinishesOp() = runBlocking {
        val src = dir("ghost-src")
        val dst = dir("ghost-dst")
        val ghost = src.resolve("ghost.txt") // لم يُنشأ
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).copy(listOf(ghost), dst, ConflictPolicy.SKIP)

        assertEquals("مصدر مفقود = عنصر فاشل لا فشل دفعة", FileOpStats(0, 1, 0), stats)
        val record = journal.op(journal.ops().single().id)!!
        assertEquals("فشل العنصر لا يفشل العملية: تُنهى FINISHED", OpState.FINISHED, record.state)
        assertEquals(1, record.failed)
        val item = journal.items(record.id).single()
        assertEquals(ItemState.FAILED, item.state)
        assertTrue(item.error.isNotEmpty())
        assertFalse(Files.exists(dst.resolve("ghost.txt")))
    }

    @Test
    fun copyUnreadableSourceCountsOneFailure() = runBlocking {
        val src = dir("locked-src")
        val dst = dir("locked-dst")
        val f = write(src.resolve("secret.txt"), "S")
        f.toFile().setReadable(false, false)
        try {
            assumeTrue("لا يمكن إسقاط صلاحية القراءة في هذه البيئة", !Files.isReadable(f))
            val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(f), dst, ConflictPolicy.SKIP)
            assertEquals(FileOpStats(0, 1, 0), stats)
            assertTrue("المصدر غير المقروء يبقى كما هو", Files.exists(f))
            assertFalse(Files.exists(dst.resolve("secret.txt")))
        } finally {
            f.toFile().setReadable(true, false)
        }
    }

    @Test
    fun copyOfRootSymlinkIsSkippedNotFollowed() = runBlocking {
        val d = dir("sym")
        val real = write(d.resolve("real.txt"), "R")
        val link = d.resolve("link")
        Files.createSymbolicLink(link, real)
        val dst = dir("sym-dst")
        val journal = MemoryOpJournal()

        val stats = FileOpEngine(journal).copy(listOf(link), dst, ConflictPolicy.REPLACE)

        assertEquals("الرابط الجذري يُتخطى لا يُتبع", FileOpStats(0, 0, 1), stats)
        val item = journal.items(journal.ops().single().id).single()
        assertEquals(ItemState.SKIPPED, item.state)
        assertEquals(CoreOpCode.SYMLINK, item.error)
        assertFalse(Files.exists(dst.resolve("link")))
        assertTrue("هدف الرابط لم يُمس", Files.exists(real))
    }

    @Test
    fun copyReplaceOntoNonEmptyDirectoryFailsWithoutWipingIt() = runBlocking {
        val src = dir("dirclash-src")
        val dst = dir("dirclash-dst")
        write(src.resolve("f.txt"), "F")
        // مسار الوجهة المُتعارَض عليه مجلد بغير محتوى فارغ.
        write(dst.resolve("f.txt/keep.txt"), "K")

        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(src.resolve("f.txt")), dst, ConflictPolicy.REPLACE)

        assertEquals("الاستبدال لا يمحو شجرة مجلد", FileOpStats(0, 1, 0), stats)
        assertEquals("K", Files.readString(dst.resolve("f.txt/keep.txt")))
    }

    /** تثبيت السلوك الحالي على هذه المنصة. */
    @Test
    fun copyReplaceOntoEmptyDirectoryAlsoFailsOnThisPlatform() = runBlocking {
        val src = dir("emptyclash-src")
        val dst = dir("emptyclash-dst")
        write(src.resolve("f.txt"), "F")
        Files.createDirectories(dst.resolve("f.txt")) // مجلد فارغ

        val stats = FileOpEngine(MemoryOpJournal()).copy(listOf(src.resolve("f.txt")), dst, ConflictPolicy.REPLACE)

        assertEquals("حتى المجلد الفارغ يُرفض في مسار النقل الذري هنا", FileOpStats(0, 1, 0), stats)
        assertTrue("المجلد يبقى مجلداً", Files.isDirectory(dst.resolve("f.txt")))
        val leftovers = Files.list(dst).use { s -> s.anyMatch { it.fileName.toString().startsWith(".fm-tmp-") } }
        assertFalse("لا ملفات مؤقتة متروكة", leftovers)
    }
}
