package org.openfilemanager.core
import org.openfilemanager.core.CoreOpCode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class ReplaceBackupRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    /** مجلد هدف «قديم» بمحتوى مستخدم أصيل قابل للتحقق منه بايتاً بايتاً. */
    private fun oldUserData(parent: Path, name: String = "docs"): Path {
        val root = parent.resolve(name)
        Files.createDirectories(root.resolve("sub"))
        Files.writeString(root.resolve("report.txt"), "ORIGINAL USER DATA - report")
        Files.writeString(root.resolve("sub/photo.bin"), "ORIGINAL USER DATA - binary")
        return root
    }

    /** سجل عملية استخراج بحالة معينة (كما تكتبها ArchiveOpEngine/الخدمة). */
    private fun extractJournal(
        state: OpState,
        targetDir: Path,
        archiveName: String = "docs.zip",
        message: String = ""
    ): Pair<FileOpJournal, String> {
        val journal = FileOpJournal(dir("journal"))
        val opId = FileOpEngine.newOpId()
        journal.startOp(
            OpRecord(
                id = opId, kind = OpKind.EXTRACT, state = OpState.RUNNING,
                sources = listOf(targetDir.resolve(archiveName).toString()),
                targetDir = targetDir.toString(),
                createdAt = System.currentTimeMillis()
            ),
            listOf(
                OpItemRecord(
                    opId = opId, index = 0, group = 0,
                    itemKind = OpItemKind.FILE, state = ItemState.PENDING,
                    src = targetDir.resolve(archiveName).toString(),
                    dst = targetDir.resolve("docs").toString(), extra = "report.txt"
                )
            )
        )
        if (state != OpState.RUNNING) {
            journal.finishOp(opId, state, 0, 1, 0, message.ifEmpty { state.name })
        }
        return journal to opId
    }

    private fun store(): ReplaceBackupStore = ReplaceBackupStore(dir("replace-store").resolve("r"))

    // 1. انقطاع قبل نقل المجلد الأصلي: سُجّل الأمر ثم ماتت العملية قبل move.

    @Test
    fun interruptedBeforeMovingOriginalKeepsOriginalIntact() {
        val targetDir = dir("case1")
        val original = oldUserData(targetDir)
        val s = store()
        val opId = "pre-move-op"
        val backup = targetDir.resolve(".fm-replace-backup-00000000-0000")
        s.record(opId, original, backup) // سُجّل… ولم يُنفّذ النقل قط

        val (journal, _) = extractJournal(OpState.RUNNING, targetDir)
        val out = ReplaceBackupRecovery.reconcile(s, journal)

        assertTrue(Files.exists(original))
        assertTrue(Files.readString(original.resolve("report.txt")).contains("ORIGINAL"))
        assertNull(s.get(opId)) // الأمر حُسم: لا شيء معلّق
        assertTrue(out.restored.isEmpty())
    }

    // 2. انقطاع بعد نقل المجلد الأصلي إلى النسخة الاحتياطية.

    @Test
    fun interruptedAfterMoveRestoresOriginalFromBackup() {
        val targetDir = dir("case2")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, opId) = extractJournal(OpState.RUNNING, targetDir)
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup) // النقل تم، ثم ماتت العملية

        val out = ReplaceBackupRecovery.reconcile(s, journal)

        assertTrue(out.restored.contains(opId))
        assertTrue(Files.exists(original))
        assertEquals("ORIGINAL USER DATA - report", Files.readString(original.resolve("report.txt")))
        assertEquals("ORIGINAL USER DATA - binary", Files.readString(original.resolve("sub/photo.bin")))
        assertFalse(Files.exists(backup))
        assertNull(s.get(opId))
    }

    // 3. انقطاع أثناء استخراج الملفات: مؤقت `.fm-extract-*` متروك أيضاً.

    @Test
    fun interruptedDuringExtractionRestoresOriginalAndSweepsOnlyTemps() {
        val targetDir = dir("case3")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, opId) = extractJournal(OpState.RUNNING, targetDir)
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)
        val extractTmp = targetDir.resolve(".fm-extract-" + java.util.UUID.randomUUID().toString().take(8))
        Files.createDirectory(extractTmp)
        Files.writeString(extractTmp.resolve("partial.txt"), "partial write")

        // المنظف العام يجتاح مؤقت الاستخراج المتقادم ولا يمسّ النسخة الاحتياطية.
        val stale = System.currentTimeMillis() - 2 * 60 * 60 * 1000
        Files.setLastModifiedTime(backup, FileTime.fromMillis(stale))
        Files.setLastModifiedTime(extractTmp, FileTime.fromMillis(stale))
        OpRecovery.cleanupStaleTemps(journal, minAgeMs = 60L * 60 * 1000)
        assertTrue("backup is user data; janitor must not touch it", Files.exists(backup))
        assertFalse("extraction temp is a temp; janitor sweeps it", Files.exists(extractTmp))

        val out = ReplaceBackupRecovery.reconcile(s, journal)
        assertTrue(out.restored.contains(opId))
        assertTrue(Files.exists(original))
        assertEquals("ORIGINAL USER DATA - report", Files.readString(original.resolve("report.txt")))
    }

    // 4. انقطاع بعد انتهاء الاستخراج وقبل اعتماد النتيجة/حذف النسخة:
    //    النشر ذري ويسبق سطر الإنهاء، فـ FINISHED = البديل منشور فعلاً.

    @Test
    fun interruptedAfterExtractionBeforeAdoptionKeepsNewAndRemovesBackup() {
        val targetDir = dir("case4")
        val s = store()
        // core الآن يخزّن رمزاً لا نصاً عربياً في السجل.
        val (journal, opId) = extractJournal(OpState.FINISHED, targetDir, message = CoreOpCode.EXTRACTED)
        val original = targetDir.resolve("docs")
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(oldUserData(targetDir), backup) // القديم في النسخة
        Files.createDirectories(original)          // البديل نُشر
        Files.writeString(original.resolve("report.txt"), "NEW EXTRACTED")

        val out = ReplaceBackupRecovery.reconcile(s, journal)

        assertTrue(out.removed.contains(opId))
        assertEquals("NEW EXTRACTED", Files.readString(original.resolve("report.txt")))
        assertFalse(Files.exists(backup))
        assertNull(s.get(opId))
    }

    // 5. فشل استخراج أرشيف تالف: العملية FAILED والنسخة تُستعاد.

    @Test
    fun corruptArchiveFailureRestoresOriginal() {
        val targetDir = dir("case5")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, opId) = extractJournal(OpState.FAILED, targetDir, message = CoreOpCode.EXTRACT_FAILED)
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)

        val out = ReplaceBackupRecovery.reconcile(s, journal)

        assertTrue(out.restored.contains(opId))
        assertEquals("ORIGINAL USER DATA - report", Files.readString(original.resolve("report.txt")))
        assertFalse(Files.exists(backup))
    }

    // 6. امتلاء التخزين أثناء الاستخراج: نفس مسار الفشل — IOException/FAILED.

    @Test
    fun storageFullDuringExtractionRestoresOriginal() {
        val targetDir = dir("case6")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, opId) =
            extractJournal(OpState.FAILED, targetDir, message = "No space left on device")
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)

        val out = ReplaceBackupRecovery.reconcile(s, journal)

        assertTrue(out.restored.contains(opId))
        assertTrue(Files.exists(original.resolve("sub/photo.bin")))
    }

    // 7. فشل إعادة تسمية النسخة الجديدة (النشر النهائي): FAILED → استعادة.

    @Test
    fun renameFailureOfNewExtractionRestoresOriginal() {
        val targetDir = dir("case7")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, opId) =
            extractJournal(OpState.FAILED, targetDir, message = "AtomicMoveNotSupportedException")
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)

        val out = ReplaceBackupRecovery.reconcile(s, journal)

        assertTrue(out.restored.contains(opId))
        assertEquals("ORIGINAL USER DATA - binary", Files.readString(original.resolve("sub/photo.bin")))
    }

    // 8. وجود ملفات متعارضة: الوجهة موجودة والعملية غير مكتملة — لا كتابة
    //    فوقها ولا حذف للنسخة؛ تُحفظ ويُبلَّغ.

    @Test
    fun conflictingTargetKeepsBackupAndReports() {
        val targetDir = dir("case8")
        val s = store()
        val (journal, opId) = extractJournal(OpState.INTERRUPTED, targetDir)
        val original = targetDir.resolve("docs")
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(oldUserData(targetDir), backup)
        Files.createDirectories(original) // وجهة ظهرت من خارج العملية
        Files.writeString(original.resolve("stranger.txt"), "conflicting content")

        val out = ReplaceBackupRecovery.reconcile(s, journal)

        assertTrue(out.kept.contains(opId))
        assertTrue("backup with undecidable state must be kept", Files.exists(backup))
        assertEquals("conflicting content", Files.readString(original.resolve("stranger.txt")))
        assertTrue(out.errors.isNotEmpty())
        assertNotNull(s.get(opId)) // الأمر باقٍ للعرض/القرار
    }

    // 9. استعادة النسخة الأصلية بعد فشل العملية: المحتوى كامل بايتاً بايتاً.

    @Test
    fun restoredOriginalIsByteIdentical() {
        val targetDir = dir("case9")
        val original = oldUserData(targetDir)
        val expectedReport = Files.readAllBytes(original.resolve("report.txt"))
        val expectedPhoto = Files.readAllBytes(original.resolve("sub/photo.bin"))
        val s = store()
        val (journal, opId) = extractJournal(OpState.CANCELLED, targetDir, message = CoreOpCode.CANCELLED)
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)

        ReplaceBackupRecovery.reconcile(s, journal)

        org.junit.Assert.assertArrayEquals(expectedReport, Files.readAllBytes(original.resolve("report.txt")))
        org.junit.Assert.assertArrayEquals(expectedPhoto, Files.readAllBytes(original.resolve("sub/photo.bin")))
    }

    // 10. لا تُحذف النسخة الاحتياطية قبل اكتمال العملية: كل مسارات التنظيف
    //     تحميها ما دامت العملية غير منتهية بنجاح.

    @Test
    fun backupSurvivesAllJanitorsWhileOperationIncomplete() {
        val targetDir = dir("case10")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, opId) = extractJournal(OpState.RUNNING, targetDir)
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)
        Files.setLastModifiedTime(backup, FileTime.fromMillis(System.currentTimeMillis() - 48 * 60 * 60 * 1000))

        // المنظف العام بمختلف أبوابه:
        OpRecovery.cleanupStaleTemps(journal, minAgeMs = 0L)
        val op = journal.op(opId)!!
        OpRecovery.cleanupTemps(journal, op)
        OpRecovery.abandonUnresumable(journal, listOf(op))
        assertTrue("no janitor may delete an in-flight replace backup", Files.exists(backup))
        assertEquals("ORIGINAL USER DATA - report", Files.readString(backup.resolve("report.txt")))

        // والتسوية تعيدها بعد أن صارت العملية منتهية بالفشل (abandon):
        val out = ReplaceBackupRecovery.reconcile(s, journal)
        assertTrue(out.restored.contains(opId))
        assertTrue(Files.exists(original))
        assertEquals("ORIGINAL USER DATA - report", Files.readString(original.resolve("report.txt")))
    }

    // إضافات حرجة: عملية حية تُترك وحدها، وسجل غائب = حالة غير قابلة للحسم.

    @Test
    fun activeOperationIsNotTouched() {
        val targetDir = dir("case11")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, opId) = extractJournal(OpState.RUNNING, targetDir)
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)

        val out = ReplaceBackupRecovery.reconcile(s, journal, activeOpIds = setOf(opId))

        assertTrue(out.restored.isEmpty())
        assertTrue(Files.exists(backup))
        assertFalse(Files.exists(original))
        assertNotNull(s.get(opId))
    }

    @Test
    fun missingJournalOpRestoresWhenNoTargetAndKeepsWhenAmbiguous() {
        val targetDir = dir("case12")
        val original = oldUserData(targetDir)
        val s = store()
        val (journal, _) = extractJournal(OpState.RUNNING, targetDir) // عملية أخرى
        val opId = "ghost-op"
        val backup = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup)
        Files.move(original, backup)

        // الوجهة غائبة: لا بديل نُشر قط — الإرجاع آمن.
        var out = ReplaceBackupRecovery.reconcile(s, journal)
        assertTrue(out.restored.contains(opId))
        assertEquals("ORIGINAL USER DATA - report", Files.readString(original.resolve("report.txt")))

        // الوجهة موجودة مع سجل غائب: حالة ملتبسة — تُحفظ النسخة.
        val backup2 = targetDir.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        s.record(opId, original, backup2)
        Files.move(original, backup2)
        Files.createDirectories(original)
        out = ReplaceBackupRecovery.reconcile(s, journal)
        assertTrue(out.kept.contains(opId))
        assertTrue(Files.exists(backup2))
        assertTrue(out.errors.any { it.contains(CoreOpCode.BACKUP_PRESERVED) })
    }
}
