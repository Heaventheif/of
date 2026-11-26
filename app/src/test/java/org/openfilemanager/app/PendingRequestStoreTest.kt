package org.openfilemanager.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import org.openfilemanager.core.MemoryOpJournal
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OpRecord
import org.openfilemanager.core.OpState

/** اختبارات التسليم الذري لطلبات العمليات. */
class PendingRequestStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun backupFile(root: File, opId: String) = File(File(root, "pending-backup"), opId)
    private fun pendingFile(root: File, token: String) = File(File(root, "pending-ops"), token)
    private fun markerFile(root: File, opId: String) = File(File(root, "active-ops"), opId)

    @Test fun holdAndReadMovesRequestToBackupByKeyedOpId() {
        val root = tmp.newFolder("store")
        val token = PendingRequestStore.write(root, PendingRequestStore.KIND_COPY, listOf("/tmp/a.txt"), "/tmp/dst")
        val opId = "op-123456"
        val request = PendingRequestStore.holdAndRead(root, token, opId)!!

        assertEquals(PendingRequestStore.KIND_COPY, request.kind)
        assertEquals(listOf("/tmp/a.txt"), request.paths)
        assertEquals("/tmp/dst", request.target)
        assertFalse("token file must be consumed", pendingFile(root, token).exists())
        assertTrue("backup must exist from the move onward", backupFile(root, opId).isFile)

        // إعادة الاستدعاء (انقطاع بين النقل والقراءة) تقرأ النسخة نفسها — متماثل.
        val again = PendingRequestStore.holdAndRead(root, token, opId)!!
        assertEquals(request, again)
        assertTrue(backupFile(root, opId).isFile)
    }

    @Test fun holdAndReadReturnsNullForMissingAndInvalidTokens() {
        val root = tmp.newFolder("store")
        assertEquals(null, PendingRequestStore.holdAndRead(root, "missingtoken1", "op-1"))
        assertEquals(null, PendingRequestStore.holdAndRead(root, "bad token!", "op-1"))
        assertEquals(null, PendingRequestStore.holdAndRead(root, "aaaabbbb", "bad id!"))
    }

    @Test fun recoverActiveReenqueuesHeldBackupWithoutMarkerOrJournal() {
        val root = tmp.newFolder("store2")
        val token = PendingRequestStore.write(root, PendingRequestStore.KIND_COPY, listOf("/tmp/a.txt"), "/tmp/dst")
        val opId = "op-abc123"
        val journal = MemoryOpJournal()
        // انقطاع بين النقل الذري وعلامة markActive: نسخة بلا علامة ولا سجل.
        PendingRequestStore.holdAndRead(root, token, opId)

        val recovered = PendingRequestStore.recoverActive(root, journal, staleMs = 60_000L)
        assertEquals(1, recovered.size)
        assertEquals(opId, recovered[0].first)
        assertEquals(PendingRequestStore.KIND_COPY, recovered[0].second.kind)
        assertTrue("backup survives recovery (it re-enters the queue)", backupFile(root, opId).isFile)
    }

    @Test fun recoverActiveClearsRunningJournalArtifactsForRecoveryDialog() {
        val root = tmp.newFolder("store3")
        val opId = "op-runnable"
        val journal = MemoryOpJournal()
        PendingRequestStore.writePending(root, opId, PendingRequestStore.KIND_MOVE, listOf("/x"), "/y")
        PendingRequestStore.markActive(root, opId)
        journal.startOp(
            OpRecord(opId, OpKind.MOVE, OpState.RUNNING, listOf("/x"), "/y", System.currentTimeMillis()),
            emptyList()
        )

        val recovered = PendingRequestStore.recoverActive(root, journal, staleMs = 60_000L)
        assertTrue("RUNNING journal records belong to the recovery dialog, not re-run", recovered.isEmpty())
        assertFalse(markerFile(root, opId).exists())
        assertFalse("backup is redundant once the journal record exists", backupFile(root, opId).exists())
    }

    @Test fun recoverActiveClearsFinishedJournalArtifactsAndStaleOrphans() {
        val root = tmp.newFolder("store4")
        val doneId = "op-finished"
        val staleId = "op-stale"
        val journal = MemoryOpJournal()
        PendingRequestStore.writePending(root, doneId, PendingRequestStore.KIND_DELETE, listOf("/x"), null)
        PendingRequestStore.markActive(root, doneId)
        journal.startOp(OpRecord(doneId, OpKind.DELETE, OpState.RUNNING, listOf("/x"), "", 1L), emptyList())
        journal.finishOp(doneId, OpState.FINISHED, 1, 0, 0, "ok")

        val staleToken = PendingRequestStore.write(root, PendingRequestStore.KIND_COPY, listOf("/a"), "/b")
        PendingRequestStore.holdAndRead(root, staleToken, staleId)
        backupFile(root, staleId).setLastModified(0L)

        assertTrue(PendingRequestStore.recoverActive(root, journal, staleMs = 60_000L).isEmpty())
        assertFalse(markerFile(root, doneId).exists())
        assertFalse(backupFile(root, doneId).exists())
        assertFalse("stale orphan backup must be purged", backupFile(root, staleId).exists())
    }

    @Test fun markActiveAndClearRoundTripThenRemovePending() {
        val root = tmp.newFolder("store5")
        val opId = "op-mark"
        PendingRequestStore.markActive(root, opId)
        assertTrue(markerFile(root, opId).isFile)
        PendingRequestStore.writePending(root, opId, PendingRequestStore.KIND_DELETE, listOf("/x"), null)
        assertTrue(backupFile(root, opId).isFile)
        PendingRequestStore.clearActive(root, opId)
        PendingRequestStore.removePending(root, opId)
        assertFalse(markerFile(root, opId).exists())
        assertFalse(backupFile(root, opId).exists())
    }

    // ---- (E7) مسارات فشل الكتابة: الرفض قبل لمس القرص، والاستثناء لا يترك أثراً ----

    /** رفض المدخلات غير الصالحة يحدث قبل إنشاء أي ملف (فلا نفايات ولا token كاذب). */
    @Test fun writeRejectsInvalidInputWithoutTouchingDisk() {
        val root = tmp.newFolder("e7-invalid")

        // قائمة مصادر فارغة
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.write(root, PendingRequestStore.KIND_COPY, emptyList(), "/dst")
        }
        // سلسلة واحدة تتجاوز حد writeUTF (65535 بايت): الرفض من فحص الحمولة
        val overUtfLimit = "/" + "a".repeat(70_000)
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.write(root, PendingRequestStore.KIND_COPY, listOf(overUtfLimit), "/dst")
        }
        // أكثر من حد العناصر (100000)
        val tooMany = List(100_001) { "/f$it" }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.write(root, PendingRequestStore.KIND_COPY, tooMany, "/dst")
        }

        // الرفض لم يُنشئ مجلد المخزن ولا أي ملف فيه
        val dir = File(root, "pending-ops")
        assertTrue("no store directory after pure rejections", !dir.exists() || dir.listFiles().isNullOrEmpty())
    }

    /** فشل الإدخال/الإخراج ينتشر (لا يُبتلع) — والدليل: فشل إنشاء مجلد المخزن. */
    @Test fun writeSurfacesIoFailureAndLeavesNoTempLitter() {
        val root = tmp.newFolder("e7-io")
        // ملفات عادية تحتل مساري مجلدي المخزن والعلامات: mkdirs سيفشل فيهما
        val dir = File(root, "pending-ops")
        assertTrue(dir.createNewFile())
        val activeDir = File(root, "active-ops")
        assertTrue(activeDir.createNewFile())

        assertThrows(IOException::class.java) {
            PendingRequestStore.write(root, PendingRequestStore.KIND_COPY, listOf("/a"), "/dst")
        }
        assertThrows(IOException::class.java) {
            PendingRequestStore.writeViewerPlaylist(root, PendingRequestStore.KIND_VIEWER_IMAGES, listOf("/a"), 0)
        }
        assertThrows(IOException::class.java) {
            PendingRequestStore.markActive(root, "op-iocase")
        }
        // المسارات ما زالت ملفات عادية (لم تتحول لمجلدات) ولا مؤقتات تحت الجذر
        assertFalse(dir.isDirectory)
        assertFalse(activeDir.isDirectory)
        root.walkTopDown().filter { it.name.startsWith(".tmp-") }.let { litter ->
            assertTrue("no temp litter after io failures", litter.none())
        }
    }

    /** writePending: معرّف غير صالح أو نوع غير معروف → رفض بلا نسخة احتياطية. */
    @Test fun writePendingRejectsInvalidOpIdOrKindWithoutBackup() {
        val root = tmp.newFolder("e7-pending")
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.writePending(root, "bad id!", PendingRequestStore.KIND_COPY, listOf("/a"), "/b")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.writePending(root, "op-valid1", "WEIRD", listOf("/a"), "/b")
        }
        val backupDir = File(root, "pending-backup")
        assertTrue("no backup after rejections", !backupDir.exists() || backupDir.listFiles().isNullOrEmpty())
    }

    /** writeViewerPlaylist: نوع غير مدعوم أو حدود فهرس خاطئة → رفض قبل القرص. */
    @Test fun writeViewerPlaylistRejectsUnsupportedKindAndBadBounds() {
        val root = tmp.newFolder("e7-playlist")
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.writeViewerPlaylist(root, "NOPE", listOf("/a"), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.writeViewerPlaylist(root, PendingRequestStore.KIND_VIEWER_IMAGES, emptyList(), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingRequestStore.writeViewerPlaylist(root, PendingRequestStore.KIND_VIEWER_IMAGES, listOf("/a"), 5)
        }
        val dir = File(root, "pending-ops")
        assertTrue("no store directory after playlist rejections", !dir.exists() || dir.listFiles().isNullOrEmpty())
    }
}
