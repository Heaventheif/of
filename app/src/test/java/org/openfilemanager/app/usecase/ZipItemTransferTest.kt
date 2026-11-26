package org.openfilemanager.app.usecase

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openfilemanager.core.ZipEditException
import org.openfilemanager.core.ZipModel
import org.openfilemanager.core.ZipRewriter
import org.openfilemanager.core.ZipSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ZipItemTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val uc = ZipItemTransfer()

    // ---------- بناء الأرشيفات المصنوعة (كاختبارات :core) ----------

    /** وقت ثابت لكل المداخل: بايتات الأرشيف المُعاد كتابته تتكرر حتمياً. */
    private val fixedTime = 1_700_000_000_000L

    private fun writeZip(p: Path, entries: List<Pair<String, String>>): Path {
        ZipArchiveOutputStream(Files.newOutputStream(p)).use { z ->
            for ((name, content) in entries) {
                val e = ZipArchiveEntry(name)
                e.time = fixedTime
                z.putArchiveEntry(e)
                if (content.isNotEmpty()) z.write(content.toByteArray(Charsets.UTF_8))
                z.closeArchiveEntry()
            }
        }
        return p
    }

    private fun twinZip(folderName: String, entries: List<Pair<String, String>>): Pair<Path, Path> {
        val folder = tmp.newFolder(folderName).toPath()
        val a = writeZip(folder.resolve("a.zip"), entries)
        val b = writeZip(folder.resolve("b.zip"), entries)
        assertArrayEquals("بناء الأرشيفين المتطابقين يجب أن يكون حتمياً",
            Files.readAllBytes(a), Files.readAllBytes(b))
        return a to b
    }

    private fun load(p: Path): ZipModel =
        ZipModel.load(p).model ?: error("أرشيف الاختبار يجب أن يُفتح للتحرير: ${ZipModel.load(p).reason}")

    /** لقطة حالة النموذج للمقارنة: (الاسم، الحجم، نوع المصدر). */
    private fun snapshot(model: ZipModel): List<Triple<String, Long, String>> = model.entries().map {
        Triple(it.name, it.size, when (it.source) {
            is ZipSource.Original -> "O"
            is ZipSource.External -> "E"
            ZipSource.Directory -> "D"
        })
    }

    private fun reasonOf(t: Throwable?): ZipEditException.Reason? = (t as? ZipEditException)?.reason

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun le64(v: Long) = le32(v and 0xFFFFFFFFL) + le32((v ushr 32) and 0xFFFFFFFFL)

    /** سجل ZIP64 موسع (كاختبارات ZipCentralDirectoryGuardTest). */
    private fun zip64Record(declared: Long, cdSize: Long, cdOffset: Long): ByteArray =
        byteArrayOf(0x50, 0x4b, 0x06, 0x06) +
            le64(44L) +
            le16(45) + le16(45) + le32(0) + le32(0) +
            le64(declared) + le64(declared) +
            le64(cdSize) + le64(cdOffset)

    private fun zip64Locator(recordOffset: Long): ByteArray =
        byteArrayOf(0x50, 0x4b, 0x06, 0x07) + le32(0) + le64(recordOffset) + le32(1)

    /**
     * أرشيف مصنوع بعدد معلن فوق السقف الافتراضي (100k) عبر ZIP64 — كأرشيفات
     * ZipCentralDirectoryGuardTest: يُرفض قبل فتح ZipFile بنفس سبب اليوم.
     * (عدّ EOCD القديم لا يتسع لرقم فوق 65535، والسقف الافتراضي أعلى منه.)
     */
    private fun craftedOverLimit(folder: Path): Path {
        val p = folder.resolve("crafted.zip")
        val payloadSize = 512
        val payload = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(payloadSize - 4) { 'x'.code.toByte() }
        val rec = zip64Record(1_000_000L, 512L, 0L)
        val loc = zip64Locator(payloadSize.toLong())
        val eocd = byteArrayOf(0x50, 0x4b, 0x05, 0x06) +
            le16(0) + le16(0) + le16(0xFFFF) + le16(0xFFFF) +
            le32(0xFFFFFFFFL) + le32(0xFFFFFFFFL) + le16(0)
        Files.write(p, payload + rec + loc + eocd)
        assertEquals("المصنوع يجب أن يُرفض بالسقف الافتراضي نفسه",
            "too-many-entries", ZipModel.load(p).reason)
        return p
    }

    /** رقعة على سجل الدليل المركزي: حجم غير مضغوط معلن ضخم لمدخل واحد. */
    private fun patchCdUncompressedSize(zip: Path, declaredSize: Long): Path {
        val bytes = Files.readAllBytes(zip)
        val sig = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        var idx = -1
        for (i in bytes.size - sig.size downTo 0) {
            if (bytes[i] == sig[0] && bytes[i + 1] == sig[1] && bytes[i + 2] == sig[2] && bytes[i + 3] == sig[3]) {
                idx = i; break
            }
        }
        assertTrue("سجل الدليل المركزي غير موجود", idx >= 0)
        bytes[idx + 24] = (declaredSize and 0xFF).toByte()
        bytes[idx + 25] = ((declaredSize shr 8) and 0xFF).toByte()
        bytes[idx + 26] = ((declaredSize shr 16) and 0xFF).toByte()
        bytes[idx + 27] = ((declaredSize shr 24) and 0xFF).toByte()
        Files.write(zip, bytes)
        return zip
    }

    private fun namesOf(zip: Path): List<String> = load(zip).entries().map { it.name }

    // ---------- المنطق القديم: نسخ حرفية من BrowserViewModel عند 8ababaa ----------

    /** ملاحظة سلوك المسار القديم كما كانت الواجهة تراها. */
    private class OldPaste(
        val readonlyMessage: Boolean,             // sayErr(ui_err_readonly_archive); return
        val otherArchiveMessage: Boolean,         // sayErr(ui_err_other_archive); return
        val pendingSourceDeletesRecorded: Int,    // pendingSourceDeletes += clip.paths
        val clipboardCleared: Boolean,            // _state.update { clipboard = null }
        val editError: ZipEditException.Reason?,  // ما كان سيمر إلى editErrorText
    )

    /**
     * BrowserViewModel.pasteIntoArchive (قبل 9d) حرفياً، مع تنفيذ كتلة editArchive
     * متزامناً في الاختبار: runCatching على الكتلة، والتسجيل المتزامن لأصول
     * الحذف بعد الإطلاق (حتى لو فشلت الكتلة لاحقاً)، ثم مسح الحافظة.
     */
    private fun oldPasteIntoArchive(
        model: ZipModel?, clipArchive: String?, currentArchive: File,
        paths: List<String>, into: String, move: Boolean,
    ): OldPaste {
        if (model == null) return OldPaste(readonlyMessage = true, otherArchiveMessage = false, 0, false, null)
        if (clipArchive != null) {
            if (File(clipArchive) != currentArchive) {
                return OldPaste(false, otherArchiveMessage = true, 0, false, null)
            }
            val err = runCatching { model.transfer(paths, into, copy = !move) }.exceptionOrNull()
            return OldPaste(false, false, 0, clipboardCleared = true, reasonOf(err))
        }
        val err = runCatching { paths.forEach { model.add(into, File(it).toPath()) } }.exceptionOrNull()
        // if (clip.move) pendingSourceDeletes += clip.paths — فور الإطلاق بغضّ النظر عن نتيجة الكتلة
        val recorded = if (move) paths.size else 0
        return OldPaste(false, false, recorded, clipboardCleared = true, reasonOf(err))
    }

    /** تنفيذ الجديد بنفس ترتيب الواجهة بعد الربط: قرار ثم تنفيذ (الاستثناء كما هو). */
    private fun newPasteIntoArchive(
        transfer: ZipItemTransfer, model: ZipModel?, clipArchive: String?, currentArchive: File,
        paths: List<String>, into: String, move: Boolean,
    ): OldPaste {
        return when (val d = transfer.planPaste(model, currentArchive, clipArchive, paths, into, move)) {
            ZipItemTransfer.PasteDecision.NoEditableModel ->
                OldPaste(readonlyMessage = true, otherArchiveMessage = false, 0, false, null)
            ZipItemTransfer.PasteDecision.FromOtherArchive ->
                OldPaste(false, otherArchiveMessage = true, 0, false, null)
            is ZipItemTransfer.PasteDecision.Paste -> {
                val err = runCatching { transfer.execute(d.op, model!!) }.exceptionOrNull()
                val recorded = if (d.pendingSourceDeletes) paths.size else 0
                OldPaste(false, false, recorded, clipboardCleared = true, reasonOf(err))
            }
        }
    }

    /** BrowserViewModel.pasteArchiveToDisk فرع move (قبل 9d) حرفياً. */
    private fun oldDeleteMoved(archive: Path, entries: List<String>): Throwable? =
        runCatching {
            val m = ZipModel.load(archive).model
                ?: throw ZipEditException(ZipEditException.Reason.UNSUPPORTED, "read-only archive")
            m.delete(entries)
            ZipRewriter.write(m)
        }.exceptionOrNull()

    /** BrowserViewModel.openArchiveEntry فرع النموذج (قبل 9d) حرفياً. */
    private fun oldExtractForView(model: ZipModel, entry: String, displayName: String, dest: File): File? =
        runCatching {
            val target = File(dest, displayName)
            when (val src = model.get(entry)?.source) {
                is ZipSource.Original -> ZipRewriter.extractOne(model.archive, src.name, target.toPath())
                is ZipSource.External -> Files.copy(src.path, target.toPath())
                else -> return@runCatching null
            }
            target
        }.getOrNull()

    /** مقارنة كاملة بين مسارين على نموذجين متطابقين. */
    private fun comparePaste(
        entries: List<Pair<String, String>>,
        clipArchive: String?,
        paths: List<String>, into: String, move: Boolean,
    ) {
        val (zipA, zipB) = twinZip("paste-${System.nanoTime()}", entries)
        val modelA = load(zipA)
        val modelB = load(zipB)
        val current = tmp.newFolder("current-${System.nanoTime()}")

        val old = oldPasteIntoArchive(modelA, clipArchive, current, paths, into, move)
        val new = newPasteIntoArchive(uc, modelB, clipArchive, current, paths, into, move)

        assertEquals("رسالة القراءة فقط", old.readonlyMessage, new.readonlyMessage)
        assertEquals("رسالة الأرشيف الآخر", old.otherArchiveMessage, new.otherArchiveMessage)
        assertEquals("تسجيل أصول الحذف", old.pendingSourceDeletesRecorded, new.pendingSourceDeletesRecorded)
        assertEquals("مسح الحافظة", old.clipboardCleared, new.clipboardCleared)
        assertEquals("سبب خطأ التحرير", old.editError, new.editError)
        assertEquals("حالة النموذج الناتجة", snapshot(modelA), snapshot(modelB))
        assertEquals("علامة dirty", modelA.dirty, modelB.dirty)
    }

    // ---------- قرار اللصق (pasteIntoArchive قبل التنفيذ) ----------

    @Test
    fun noEditableModelMatchesOldReadonlyGuard() {
        // لا نموذج تحرير: رسالة القراءة فقط وعودة دون مسح الحافظة ولا تسجيل أصول
        val old = oldPasteIntoArchive(null, null, tmp.root, listOf("x"), "", false)
        val new = newPasteIntoArchive(uc, null, null, tmp.root, listOf("x"), "", false)
        assertTrue(old.readonlyMessage)
        assertTrue(new.readonlyMessage)
        assertFalse(old.clipboardCleared)
        assertFalse(new.clipboardCleared)
        assertEquals(old.pendingSourceDeletesRecorded, new.pendingSourceDeletesRecorded)
        assertEquals(old.editError, new.editError)
        // الترتيب نفسه: نموذج مفقود يسبق فحص مصدر الحافظة
        assertTrue(uc.planPaste(null, File("/any.zip"), "/other.zip", listOf("x"), "", true)
            is ZipItemTransfer.PasteDecision.NoEditableModel)
    }

    @Test
    fun otherArchiveMatchesOldGuard() {
        // حافظة من أرشيف آخر: رسالة الأرشيف الآخر وعودة دون مسح الحافظة
        val (zipA, zipB) = twinZip("other", listOf("a.txt" to "A"))
        val foreign = tmp.newFolder("elsewhere").toPath().resolve("other.zip").toAbsolutePath().toString()
        val old = oldPasteIntoArchive(load(zipA), foreign, zipA.toFile(), listOf("a.txt"), "", false)
        val new = newPasteIntoArchive(uc, load(zipB), foreign, zipB.toFile(), listOf("a.txt"), "", false)
        assertTrue(old.otherArchiveMessage)
        assertTrue(new.otherArchiveMessage)
        assertFalse(old.clipboardCleared)
        assertFalse(new.clipboardCleared)
        assertEquals("النموذج لا يتغير عند الرفض", snapshot(load(zipA)), snapshot(load(zipB)))
    }

    @Test
    fun sameArchivePasteDecisionMatchesOldTransfer() {
        // الأرشيف نفسه: القرار Transfer وcopy=!move، لا أصول حذف، والحافظة تُمسح
        val zip = writeZip(tmp.newFolder("same").toPath().resolve("same.zip"), listOf("a.txt" to "A", "sub/" to ""))
        val clip = zip.toAbsolutePath().toString()
        val current = zip.toFile()

        val oldCopy = oldPasteIntoArchive(load(zip), clip, current, listOf("a.txt"), "sub/", move = false)
        val newCopy = newPasteIntoArchive(uc, load(zip), clip, current, listOf("a.txt"), "sub/", move = false)
        assertEquals(oldCopy.editError, newCopy.editError)
        assertTrue(newCopy.clipboardCleared)
        assertEquals("نسخ داخل الأرشيف لا يسجل أصول حذف", 0, newCopy.pendingSourceDeletesRecorded)
        val dCopy = uc.planPaste(load(zip), current, clip, listOf("a.txt"), "sub/", false)
        assertEquals(ZipItemTransfer.PasteOp.Transfer(listOf("a.txt"), "sub/", copy = true),
            (dCopy as ZipItemTransfer.PasteDecision.Paste).op)

        val oldMove = oldPasteIntoArchive(load(zip), clip, current, listOf("a.txt"), "sub/", move = true)
        val newMove = newPasteIntoArchive(uc, load(zip), clip, current, listOf("a.txt"), "sub/", move = true)
        assertEquals(oldMove.editError, newMove.editError)
        assertEquals(oldMove.pendingSourceDeletesRecorded, newMove.pendingSourceDeletesRecorded)
        assertEquals(0, newMove.pendingSourceDeletesRecorded)
        val dMove = uc.planPaste(load(zip), current, clip, listOf("a.txt"), "sub/", true)
        assertEquals(ZipItemTransfer.PasteOp.Transfer(listOf("a.txt"), "sub/", copy = false),
            (dMove as ZipItemTransfer.PasteDecision.Paste).op)
    }

    @Test
    fun diskPasteDecisionMatchesOldAddAndRecordsSourcesOnMove() {
        val zip = writeZip(tmp.newFolder("dp").toPath().resolve("z.zip"), listOf("a.txt" to "A"))
        val model = load(zip)
        val d = uc.planPaste(model, File("/x.zip"), null, listOf("/f.txt", "/g.txt"), "sub/", true)
        assertTrue(d is ZipItemTransfer.PasteDecision.Paste)
        d as ZipItemTransfer.PasteDecision.Paste
        assertEquals(ZipItemTransfer.PasteOp.AddFromDisk(listOf("/f.txt", "/g.txt"), "sub/"), d.op)
        assertTrue("نقل من القرص يسجل الأصول", d.pendingSourceDeletes)
        val dCopy = uc.planPaste(model, File("/x.zip"), null, listOf("/f.txt"), "sub/", false)
        assertTrue(dCopy is ZipItemTransfer.PasteDecision.Paste)
        assertFalse("نسخ من القرص لا يسجل أصولاً",
            (dCopy as ZipItemTransfer.PasteDecision.Paste).pendingSourceDeletes)
    }

    // ---------- تنفيذ اللصق على نموذجين متطابقين ----------

    @Test
    fun transferCopyProducesSameModelAsOldInline() {
        comparePaste(
            listOf("a.txt" to "A", "b.txt" to "B", "sub/" to ""),
            clipArchive = null, paths = listOf("a.txt"), into = "sub/", move = false,
        )
    }

    @Test
    fun transferMoveProducesSameModelAsOldInline() {
        comparePaste(
            listOf("a.txt" to "A", "b.txt" to "B", "sub/" to ""),
            clipArchive = null, paths = listOf("a.txt"), into = "sub/", move = true,
        )
    }

    @Test
    fun transferCopyDuplicateNameGetsSuffixLikeOld() {
        // النسخ إلى مجلد يحوي اسماً متطابقاً: لاحقة « (n)» لا كتابة فوق
        comparePaste(
            listOf("a.txt" to "A", "sub/" to "", "sub/a.txt" to "old"),
            clipArchive = null, paths = listOf("a.txt"), into = "sub/", move = false,
        )
    }

    @Test
    fun transferIntoMissingFolderFailsSameAsOld() {
        comparePaste(
            listOf("a.txt" to "A"),
            clipArchive = null, paths = listOf("a.txt"), into = "nope/", move = false,
        )
    }

    @Test
    fun transferFolderIntoItselfFailsSameAsOld() {
        comparePaste(
            listOf("top/" to "", "top/sub/" to "", "top/inner.txt" to "I"),
            clipArchive = null, paths = listOf("top/"), into = "top/sub/", move = false,
        )
    }

    @Test
    fun transferMissingEntryFailsSameAsOld() {
        comparePaste(
            listOf("a.txt" to "A"),
            clipArchive = null, paths = listOf("ghost.txt"), into = "", move = false,
        )
    }

    @Test
    fun addFromDiskProducesSameModelAsOld() {
        val src = tmp.newFolder("src").toPath()
        Files.write(src.resolve("data.txt"), "hello".toByteArray())
        Files.write(src.resolve("pic.png"), byteArrayOf(1, 2, 3))
        val paths = listOf(src.resolve("data.txt").toString(), src.resolve("pic.png").toString())
        comparePaste(listOf("a.txt" to "A", "sub/" to ""), clipArchive = null, paths = paths, into = "sub/", move = false)
        // النقل من القرص: الأصول تُسجَّل في المسارين كليهما بالتسلسل نفسه
        comparePaste(listOf("a.txt" to "A", "sub/" to ""), clipArchive = null, paths = paths, into = "sub/", move = true)
    }

    @Test
    fun addDuplicateNameGetsSuffixLikeOld() {
        val src = tmp.newFolder("src-dup").toPath()
        Files.write(src.resolve("data.txt"), "hello".toByteArray())
        comparePaste(
            listOf("a.txt" to "A", "data.txt" to "existing"),
            clipArchive = null, paths = listOf(src.resolve("data.txt").toString()), into = "", move = false,
        )
    }

    @Test
    fun addUnsafeNameRejectedSameAsOld() {
        // اسم بحرف ممنوع («\») على نظام يقبل ذلك في أسماء الملفات: رفض
        // INVALID_NAME قبل أي تغيير على النموذج وdirty يبقى كما هو.
        val src = tmp.newFolder("src-bad").toPath()
        val bad = src.resolve("bad\\name.txt")
        Files.write(bad, "x".toByteArray())
        val (zipA, zipB) = twinZip("unsafe", listOf("a.txt" to "A"))
        val modelA = load(zipA)
        val modelB = load(zipB)
        val old = oldPasteIntoArchive(modelA, null, tmp.root, listOf(bad.toString()), "", move = false)
        val new = newPasteIntoArchive(uc, modelB, null, tmp.root, listOf(bad.toString()), "", move = false)
        assertEquals(ZipEditException.Reason.INVALID_NAME, old.editError)
        assertEquals("نفس السبب", old.editError, new.editError)
        assertEquals("النموذج لم يتغير في القديم", listOf(Triple("a.txt", 1L, "O")), snapshot(modelA))
        assertEquals("النموذج لم يتغير في الجديد", listOf(Triple("a.txt", 1L, "O")), snapshot(modelB))
        assertFalse("dirty لا يتغير عند الرفض", modelB.dirty)
    }

    @Test
    fun addIntoMissingFolderFailsSameAsOld() {
        val src = tmp.newFolder("src-miss").toPath()
        Files.write(src.resolve("f.txt"), "f".toByteArray())
        comparePaste(
            listOf("a.txt" to "A"),
            clipArchive = null, paths = listOf(src.resolve("f.txt").toString()), into = "nope/", move = false,
        )
    }

    // ---------- خطوة «النقل» إلى القرص: حذف المنقول من ملف الأرشيف ----------

    @Test
    fun deleteMovedEntriesRewritesSameZipBytesAsOldInline() {
        val (zipA, zipB) = twinZip("moveout", listOf("a.txt" to "A", "b.txt" to "B", "c.txt" to "C"))
        assertNull(oldDeleteMoved(zipA, listOf("a.txt")))
        assertTrue(uc.deleteMovedEntries(zipB, listOf("a.txt")) is ZipItemTransfer.MoveOutResult.Deleted)
        assertArrayEquals("نفس بايتات الأرشيف بعد إعادة الكتابة",
            Files.readAllBytes(zipA), Files.readAllBytes(zipB))
        assertEquals(listOf("b.txt", "c.txt"), namesOf(zipB))
        java.util.zip.ZipFile(zipB.toFile()).use { zf ->
            assertEquals("B", String(zf.getInputStream(zf.getEntry("b.txt")).readBytes()))
            assertEquals("C", String(zf.getInputStream(zf.getEntry("c.txt")).readBytes()))
        }
    }

    @Test
    fun deleteMovedEntriesUnloadableArchiveFailsSameAsOld() {
        val zip = craftedOverLimit(tmp.newFolder("moveout-bad").toPath())
        val oldErr = oldDeleteMoved(zip, listOf("a.txt"))
        val newResult = uc.deleteMovedEntries(zip, listOf("a.txt"))
        assertTrue(newResult is ZipItemTransfer.MoveOutResult.Failed)
        val newErr = (newResult as ZipItemTransfer.MoveOutResult.Failed).error
        assertEquals("نفس السبب (قراءة فقط)", reasonOf(oldErr), reasonOf(newErr))
        assertEquals(ZipEditException.Reason.UNSUPPORTED, reasonOf(newErr))
        assertEquals("read-only archive", newErr.message)
    }

    @Test
    fun deleteMovedEntriesRespectsInjectedEntryCapLikeOld() {
        // القديم بالسقف الافتراضي والجديد بلا حقن: نجاح مطابق بالبايتات؛
        // الجديد بسقف محقون أصغر: رفض قبل الفتح بنفس خطأ القراءة فقط.
        val folder = tmp.newFolder("moveout-cap").toPath()
        val entries = (1..5).map { "e$it.txt" to "x$it" }
        val zipA = writeZip(folder.resolve("a.zip"), entries)
        val zipB = writeZip(folder.resolve("b.zip"), entries)
        assertEquals(Files.readAllBytes(zipA).size, Files.readAllBytes(zipB).size)
        assertArrayEquals("توائم البناء", Files.readAllBytes(zipA), Files.readAllBytes(zipB))
        assertNull(oldDeleteMoved(zipA, listOf("e1.txt")))
        assertTrue(ZipItemTransfer().deleteMovedEntries(zipB, listOf("e1.txt"))
            is ZipItemTransfer.MoveOutResult.Deleted)
        assertArrayEquals("الافتراضي يطابق القديم بالبايتات", Files.readAllBytes(zipA), Files.readAllBytes(zipB))

        val capped = ZipItemTransfer(maxEntries = { 3 })
        val r = capped.deleteMovedEntries(zipA, listOf("e1.txt"))
        assertTrue(r is ZipItemTransfer.MoveOutResult.Failed)
        val err = (r as ZipItemTransfer.MoveOutResult.Failed).error
        assertEquals(ZipEditException.Reason.UNSUPPORTED, reasonOf(err))
        assertEquals("read-only archive", err.message)
        // الأرشيف لم يُمَس عند الرفض
        assertEquals(4, namesOf(zipA).size)
    }

    @Test
    fun deleteMovedEntriesUnknownNamesStillRewriteLikeOld() {
        // أسماء غير موجودة: حذف صامت ثم إعادة كتابة كاملة كما اليوم
        val (zipA, zipB) = twinZip("moveout-ghost", listOf("a.txt" to "A", "b.txt" to "B"))
        assertNull(oldDeleteMoved(zipA, listOf("ghost.txt")))
        assertTrue(uc.deleteMovedEntries(zipB, listOf("ghost.txt")) is ZipItemTransfer.MoveOutResult.Deleted)
        assertArrayEquals("نفس بايتات إعادة الكتابة بلا حذف فعلي",
            Files.readAllBytes(zipA), Files.readAllBytes(zipB))
        assertEquals(listOf("a.txt", "b.txt"), namesOf(zipB))
    }

    // ---------- استخراج مدخل واحد للعرض ----------

    @Test
    fun extractOriginalEntryWritesSameBytesAsOldInline() {
        val (zipA, zipB) = twinZip("view-orig", listOf("docs/readme.txt" to "hello zip"))
        val destOld = tmp.newFolder("dest-old")
        val destNew = tmp.newFolder("dest-new")
        val fileOld = oldExtractForView(load(zipA), "docs/readme.txt", "readme.txt", destOld)
        val fileNew = runCatching { uc.extractEntryForView(load(zipB), "docs/readme.txt", "readme.txt", destNew) }.getOrNull()
        assertNotNull(fileOld)
        assertNotNull(fileNew)
        assertEquals(destOld.resolve("readme.txt").absolutePath, fileOld!!.absolutePath)
        assertEquals(destNew.resolve("readme.txt").absolutePath, fileNew!!.absolutePath)
        assertArrayEquals("نفس البايتات المكتوبة", fileOld.readBytes(), fileNew.readBytes())
        assertEquals("hello zip", fileNew.readText())
    }

    @Test
    fun extractExternalEntryWritesSameBytesAsOldInline() {
        val (zipA, zipB) = twinZip("view-ext", listOf("a.txt" to "A"))
        val src = tmp.newFolder("src-ext").toPath()
        Files.write(src.resolve("added.txt"), "added content".toByteArray())
        val modelA = load(zipA)
        val modelB = load(zipB)
        assertEquals("added.txt", modelA.add("", src.resolve("added.txt")))
        assertEquals("added.txt", modelB.add("", src.resolve("added.txt")))
        val destOld = tmp.newFolder("dest-ext-old")
        val destNew = tmp.newFolder("dest-ext-new")
        val fileOld = oldExtractForView(modelA, "added.txt", "added.txt", destOld)
        val fileNew = runCatching { uc.extractEntryForView(modelB, "added.txt", "added.txt", destNew) }.getOrNull()
        assertNotNull(fileOld); assertNotNull(fileNew)
        assertArrayEquals("نفس البايتات المكتوبة", fileOld!!.readBytes(), fileNew!!.readBytes())
        assertEquals("added content", fileNew.readText())
    }

    @Test
    fun extractMissingOrDirectoryEntryReturnsNullLikeOld() {
        val (zipA, zipB) = twinZip("view-null", listOf("a.txt" to "A", "folder/" to ""))
        val dest = tmp.newFolder("dest-null")
        val modelA = load(zipA)
        val modelB = load(zipB)
        assertNull(oldExtractForView(modelA, "ghost.txt", "ghost.txt", dest))
        assertNull(runCatching { uc.extractEntryForView(modelB, "ghost.txt", "ghost.txt", dest) }.getOrNull())
        assertNull(oldExtractForView(modelA, "folder/", "folder", dest))
        assertNull(runCatching { uc.extractEntryForView(modelB, "folder/", "folder", dest) }.getOrNull())
        assertTrue("لا ملف نصف مكتوب", dest.listFiles().isNullOrEmpty())
    }

    @Test
    fun extractOversizedEntryRejectedLikeOld() {
        // مدخل معلن أكبر من سقف 512 م.ب: رفض قبل قراءة أي بايت — كلاهما null
        // والمجلد الهدف يبقى فارغاً (لا ملف نصف مكتوب).
        val (zipA, zipB) = twinZip("view-big", listOf("big.bin" to "x"))
        val over600MB = 600L * 1024 * 1024
        patchCdUncompressedSize(zipA, over600MB)
        patchCdUncompressedSize(zipB, over600MB)
        val destOld = tmp.newFolder("dest-big-old")
        val destNew = tmp.newFolder("dest-big-new")
        val fileOld = oldExtractForView(load(zipA), "big.bin", "big.bin", destOld)
        val fileNew = runCatching { uc.extractEntryForView(load(zipB), "big.bin", "big.bin", destNew) }.getOrNull()
        assertNull(fileOld)
        assertNull(fileNew)
        assertTrue("لا مخلفات في الهدف القديم", destOld.listFiles().isNullOrEmpty())
        assertTrue("لا مخلفات في الهدف الجديد", destNew.listFiles().isNullOrEmpty())
    }
}
