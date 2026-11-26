package org.openfilemanager.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class FileOpEngineResumeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    /** يبني سجل عملية منقطعة كما يتركه موت العملية، ويعيد (السجل، المعرّف). */
    private fun interruptedJournal(
        name: String,
        opId: String,
        kind: OpKind,
        sources: List<Path>,
        targetDir: Path,
        items: List<OpItemRecord>,
        state: OpState = OpState.RUNNING // RUNNING = موت مفاجئ بلا سطر إنهاء
    ): Pair<FileOpJournal, String> {
        val journal = FileOpJournal(tmp.newFolder(name).toPath().resolve("ops"))
        journal.startOp(
            OpRecord(
                id = opId, kind = kind, state = OpState.RUNNING,
                sources = sources.map { it.toString() },
                targetDir = if (kind == OpKind.DELETE) "" else targetDir.toString(),
                createdAt = System.currentTimeMillis()
            ),
            items
        )
        if (state != OpState.RUNNING) journal.finishOp(opId, state, 0, 0, 0, CoreOpCode.OP_INTERRUPTED)
        return journal to opId
    }

    private fun item(
        opId: String, index: Int, kind: OpItemKind, state: ItemState,
        src: Path, dst: Path, extra: String = "", size: Long = 1, group: Int = 0
    ): OpItemRecord = OpItemRecord(
        opId = opId, index = index, group = group, itemKind = kind, state = state,
        src = src.toString(), dst = dst.toString(), extra = extra, size = size
    )

    // ---------- شكل رفض الاستئناف ----------

    @Test
    fun resumeUnknownOpIdIsRejected() = runBlocking {
        val engine = FileOpEngine(FileOpJournal(dir("r-unknown").resolve("ops")))
        try {
            engine.resume("missing-op", ConflictPolicy.SKIP)
            fail()
        } catch (e: IOException) {
            // core الآن يُرجع رمزاً لا نصاً عربياً؛ نتأكد من الرمز.
            assertTrue(e.message!!.contains(CoreOpCode.NO_OPERATION))
        }
    }

    @Test
    fun resumeOperationWithNoItemsIsRejected() = runBlocking {
        val journal = FileOpJournal(dir("r-empty").resolve("ops"))
        val opId = FileOpEngine.newOpId()
        journal.startOp(
            OpRecord(id = opId, kind = OpKind.COPY, state = OpState.RUNNING, sources = emptyList(), targetDir = "", createdAt = 0L),
            emptyList()
        )
        try {
            FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)
            fail()
        } catch (e: IOException) {
            // core الآن يُرجع رمزاً لا نصاً عربياً؛ نتأكد من الرمز.
            assertTrue(e.message!!.contains(CoreOpCode.OP_EMPTY))
        }
        assertEquals("الرفض لا ينهي العملية", OpState.RUNNING, journal.op(opId)!!.state)
    }

    @Test
    fun resumeRenameKindIsRejected() = runBlocking {
        val d = dir("r-rename")
        val journal = FileOpJournal(d.resolve("ops"))
        val opId = FileOpEngine.newOpId()
        journal.startOp(
            OpRecord(id = opId, kind = OpKind.RENAME, state = OpState.RUNNING, sources = emptyList(), targetDir = "", createdAt = 0L),
            listOf(item(opId, 0, OpItemKind.FILE, ItemState.PENDING, d.resolve("a"), d.resolve("b")))
        )
        try {
            FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains(CoreOpCode.RENAME_RECOVERED_BY_ENGINE))
        }
        assertEquals(OpState.RUNNING, journal.op(opId)!!.state)
    }

    @Test
    fun resumeCancelledStateIsRejected() = runBlocking {
        val d = dir("r-cancelled")
        val journal = FileOpJournal(d.resolve("ops"))
        val opId = FileOpEngine.newOpId()
        journal.startOp(
            OpRecord(id = opId, kind = OpKind.COPY, state = OpState.RUNNING, sources = emptyList(), targetDir = d.toString(), createdAt = 0L),
            listOf(item(opId, 0, OpItemKind.FILE, ItemState.PENDING, d.resolve("a"), d.resolve("a")))
        )
        journal.finishOp(opId, OpState.CANCELLED, 0, 0, 0, CoreOpCode.CANCELLED)
        try {
            FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains(CoreOpCode.OP_ALREADY_FINISHED))
        }
    }

    // ---------- استئناف النسخ: العدّ والتنفيذ ----------

    @Test
    fun resumeInterruptedCountsDoneAndSkippedAndExecutesPendingOnly() = runBlocking {
        val srcDir = dir("r1-src")
        val target = dir("r1-dst")
        write(srcDir.resolve("done.txt"), "changed-on-disk")
        write(srcDir.resolve("p1.txt"), "content-1")
        write(target.resolve("done.txt"), "original")
        val journal = FileOpJournal(dir("r1-j").resolve("ops"))
        val opId = FileOpEngine.newOpId()
        journal.startOp(
            OpRecord(id = opId, kind = OpKind.COPY, state = OpState.RUNNING,
                sources = listOf(srcDir.toString()), targetDir = target.toString(), createdAt = 0L),
            listOf(
                item(opId, 0, OpItemKind.FILE, ItemState.DONE, srcDir.resolve("done.txt"), target.resolve("done.txt"), group = 0),
                item(opId, 1, OpItemKind.FILE, ItemState.SKIPPED, srcDir.resolve("skipped.txt"), target.resolve("skipped.txt"), group = 1),
                item(opId, 2, OpItemKind.FILE, ItemState.PENDING, srcDir.resolve("p1.txt"), target.resolve("p1.txt"), group = 2)
            )
        )
        journal.finishOp(opId, OpState.INTERRUPTED, 0, 0, 0, CoreOpCode.OP_INTERRUPTED)

        val stats = FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)

        assertEquals("DONE معدودة فقط، SKIPPED معدودة فقط، PENDING نُفّذت", FileOpStats(2, 0, 1), stats)
        assertEquals("العنصر DONE لا يُعاد تنفيذه", "original", Files.readString(target.resolve("done.txt")))
        assertFalse("العنصر SKIPPED لا يُنفّذ", Files.exists(target.resolve("skipped.txt")))
        assertEquals("content-1", Files.readString(target.resolve("p1.txt")))
        val record = journal.op(opId)!!
        assertEquals(OpState.FINISHED, record.state)
        assertEquals(2, record.ok)
        assertEquals(1, record.skipped)
    }

    @Test
    fun resumeReexecutesFailedItems() = runBlocking {
        val srcDir = dir("r2-src")
        val target = dir("r2-dst")
        write(srcDir.resolve("f0.txt"), "F0")
        write(srcDir.resolve("p0.txt"), "P0")
        val opId = FileOpEngine.newOpId()
        val (journal, _) = interruptedJournal(
            "r2-j", opId, OpKind.COPY, listOf(srcDir), target,
            listOf(
                item(opId, 0, OpItemKind.FILE, ItemState.FAILED, srcDir.resolve("f0.txt"), target.resolve("f0.txt"), group = 0),
                item(opId, 1, OpItemKind.FILE, ItemState.PENDING, srcDir.resolve("p0.txt"), target.resolve("p0.txt"), group = 1)
            )
        )

        val stats = FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)

        assertEquals("عنصر FAILED يُعاد تنفيذه عند الاستئناف", FileOpStats(2, 0, 0), stats)
        assertEquals("F0", Files.readString(target.resolve("f0.txt")))
        assertEquals("P0", Files.readString(target.resolve("p0.txt")))
    }

    @Test
    fun resumeMissingSourceCountsFailureAndFinishes() = runBlocking {
        val srcDir = dir("r3-src")
        val target = dir("r3-dst")
        val ghost = srcDir.resolve("ghost.txt") // اختفى بين الجولتين
        val opId = FileOpEngine.newOpId()
        val (journal, _) = interruptedJournal(
            "r3-j", opId, OpKind.COPY, listOf(srcDir), target,
            listOf(item(opId, 0, OpItemKind.FILE, ItemState.PENDING, ghost, target.resolve("ghost.txt")))
        )

        val stats = FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(0, 1, 0), stats)
        val record = journal.op(opId)!!
        assertEquals("فشل عنصر لا يفشل الاستئناف", OpState.FINISHED, record.state)
        assertEquals(1, record.failed)
        assertEquals(ItemState.FAILED, journal.items(opId).single().state)
        assertFalse(Files.exists(target.resolve("ghost.txt")))
    }

    // ---------- استئناف بأنواع عناصر أخرى ----------

    @Test
    fun resumeTreeMoveKindCompletesRename() = runBlocking {
        val srcDir = dir("r4-src")
        val target = dir("r4-dst")
        write(srcDir.resolve("moved-dir/inner.txt"), "I")
        val opId = FileOpEngine.newOpId()
        val (journal, _) = interruptedJournal(
            "r4-j", opId, OpKind.MOVE, listOf(srcDir.resolve("moved-dir")), target,
            listOf(item(opId, 0, OpItemKind.TREE_MOVE, ItemState.PENDING, srcDir.resolve("moved-dir"), target.resolve("moved-dir")))
        )

        val stats = FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertEquals("I", Files.readString(target.resolve("moved-dir/inner.txt")))
        assertFalse(Files.exists(srcDir.resolve("moved-dir")))
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    @Test
    fun resumeTreeDeleteKindCompletesDeletion() = runBlocking {
        val d = dir("r5")
        write(d.resolve("tree/a.txt"), "A")
        write(d.resolve("tree/sub/b.txt"), "B")
        val opId = FileOpEngine.newOpId()
        val (journal, _) = interruptedJournal(
            "r5-j", opId, OpKind.DELETE, listOf(d.resolve("tree")), d,
            listOf(item(opId, 0, OpItemKind.TREE_DELETE, ItemState.PENDING, d.resolve("tree"), d.resolve("tree")))
        )

        val stats = FileOpEngine(journal).resume(opId, ConflictPolicy.SKIP)

        assertEquals(FileOpStats(1, 0, 0), stats)
        assertFalse(Files.exists(d.resolve("tree")))
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    /**
     * استئناف دمج نقل منقط وسط الدمج: نقاط تفتيش الدمج المسجلة (فهارس من نطاق
     * MERGE_SUB_INDEX_BASE) تُوائم بمسار المصدر — المنجز لا يُعاد والمتبقي يُنقل.
     */
    @Test
    fun resumeInterruptedMergeMoveCompletesRemainingOnly() = runBlocking {
        val srcDir = dir("r6-src")
        val target = dir("r6-dst")
        val srcD = srcDir.resolve("d")
        val dstD = target.resolve("d")
        write(srcD.resolve("f1.txt"), "F1") // لم يُنقل بعد
        write(dstD.resolve("f0.txt"), "F0")

        val journal = FileOpJournal(dir("r6-j").resolve("ops"))
        val opId = FileOpEngine.newOpId()
        journal.startOp(
            OpRecord(id = opId, kind = OpKind.MOVE, state = OpState.RUNNING,
                sources = listOf(srcD.toString()), targetDir = target.toString(), createdAt = 0L),
            listOf(item(opId, 0, OpItemKind.TREE_MOVE, ItemState.PENDING, srcD, dstD))
        )
        val mergeBase = Int.MAX_VALUE / 2
        journal.addItems(
            opId, listOf(
                item(opId, mergeBase, OpItemKind.DIR_CREATE, ItemState.DONE, srcD, dstD),
                item(opId, mergeBase + 1, OpItemKind.FILE, ItemState.DONE, srcD.resolve("f0.txt"), dstD.resolve("f0.txt"), extra = "f0.txt"),
                item(opId, mergeBase + 2, OpItemKind.FILE, ItemState.PENDING, srcD.resolve("f1.txt"), dstD.resolve("f1.txt"), extra = "f1.txt")
            )
        )

        val stats = FileOpEngine(journal).resume(opId, ConflictPolicy.REPLACE)

        assertEquals("نقطتا التفتيش المنجزة تُعدّان + الملف المتبقي", FileOpStats(3, 0, 0), stats)
        assertEquals("المنقول سلفاً لا يُمس", "F0", Files.readString(dstD.resolve("f0.txt")))
        assertEquals("F1", Files.readString(dstD.resolve("f1.txt")))
        assertFalse("المصدر أُفرغ وحُذف", Files.exists(srcD))
        val root = journal.items(opId).first { it.itemKind == OpItemKind.TREE_MOVE }
        assertEquals(ItemState.DONE, root.state)
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    @Test
    fun resumeWithCancellationMarksCancelledAndRethrows() = runBlocking {
        val srcDir = dir("r7-src")
        val target = dir("r7-dst")
        write(srcDir.resolve("a.txt"), "A")
        val opId = FileOpEngine.newOpId()
        val (journal, _) = interruptedJournal(
            "r7-j", opId, OpKind.COPY, listOf(srcDir), target,
            listOf(item(opId, 0, OpItemKind.FILE, ItemState.PENDING, srcDir.resolve("a.txt"), target.resolve("a.txt")))
        )
        val engine = FileOpEngine(journal, isCancelled = { true })

        try {
            engine.resume(opId, ConflictPolicy.SKIP)
            fail()
        } catch (_: OperationCancelledException) {
        }
        assertEquals(OpState.CANCELLED, journal.op(opId)!!.state)
        assertEquals(0, journal.op(opId)!!.ok)
    }
}
