package org.openfilemanager.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openfilemanager.app.reader.PptxLoad
import org.openfilemanager.core.ArchiveLimitException
import org.openfilemanager.core.pptx.PptxException

class ArchiveRejectionsTest {

    /** قيمة العلم عند بناء المثال (JUnit4 يبني مثالاً لكل اختبار) تُعاد بعد كل اختبار. */
    private val flagOnEntry: Boolean = FeatureFlags.archiveRejectionFeedback

    @After
    fun restoreFlag() {
        FeatureFlags.archiveRejectionFeedback = flagOnEntry
    }

    // ---- (أ) تجاوز حد العناصر: السبب المحدد ومعرفه النصي، عبر الاستثناء والرسالة الخام ----
    @Test
    fun tooManyEntriesMapsToItsSpecificReasonAndMessage() {
        val e = ArchiveLimitException("too many entries (> 5000)")
        assertEquals(ArchiveRejections.Reason.TOO_MANY_ENTRIES, ArchiveRejections.reasonOf(e))
        assertEquals(
            R.string.archive_err_too_many_entries,
            ArchiveRejections.messageRes(ArchiveRejections.Reason.TOO_MANY_ENTRIES)
        )
        // مسار الخدمة: المحرك دوّن الرسالة الخام نفسها في السجل.
        assertEquals(ArchiveRejections.Reason.TOO_MANY_ENTRIES, ArchiveRejections.reasonOf("too many entries (> 20000)"))
    }

    @Test
    fun centralDirectoryTooLargeMapsToItsSpecificReasonAndMessage() {
        val e = ArchiveLimitException("central directory exceeds 67108864 bytes")
        assertEquals(ArchiveRejections.Reason.CENTRAL_DIRECTORY_TOO_LARGE, ArchiveRejections.reasonOf(e))
        assertEquals(
            R.string.archive_err_central_directory_too_large,
            ArchiveRejections.messageRes(ArchiveRejections.Reason.CENTRAL_DIRECTORY_TOO_LARGE)
        )
    }

    @Test
    fun pptxOverBudgetMapsToTheExistingTooLargeStringThroughBothPaths() {
        val e = PptxException(PptxException.Reason.TOO_LARGE)
        assertEquals(ArchiveRejections.Reason.DECODED_OVER_BUDGET, ArchiveRejections.reasonOf(e))
        assertEquals(
            R.string.rd_err_too_large,
            ArchiveRejections.messageRes(ArchiveRejections.Reason.DECODED_OVER_BUDGET)
        )
        FeatureFlags.archiveRejectionFeedback = true
        assertEquals("العلم مفعّلاً: الخريطة تعيد النص الموجود نفسه", R.string.rd_err_too_large, PptxLoad.messageFor(e))
        FeatureFlags.archiveRejectionFeedback = false
        assertEquals("العلم مطفأً: التحويل القديم الحرفي يعطي النص نفسه", R.string.rd_err_too_large, PptxLoad.messageFor(e))
    }

    // ---- (د) لا تسريب مسارات: الرسائل الحاملة لمسارات لا تنتج سبباً محدداً ----
    @Test
    fun pathCarryingExceptionsNeverProduceASpecificReason() {
        assertNull(ArchiveRejections.reasonOf("compression ratio limit exceeded: /storage/emulated/0/secret/x.bin"))
        assertNull(ArchiveRejections.reasonOf("unreadable directory: /data/user/0/app/files"))
        assertNull(ArchiveRejections.reasonOf(null))
        assertNull(ArchiveRejections.reasonOf(""))
        assertNull(ArchiveRejections.reasonOf("zip central directory size/offset (1/2) exceed file length 3"))
        assertNull(ArchiveRejections.reasonOf(IllegalStateException("boom /data/secret/payload")))
        // ومعرفات النصوص المستهدفة ثابتة بلا معاملات: messageRes(reason) لا تأخذ
        // أي قيمة من الاستثناء فلا يمكن أن تُقحم مساره في النص الجديد أبداً.
        assertEquals(
            R.string.archive_err_too_many_entries,
            ArchiveRejections.messageRes(ArchiveRejections.Reason.TOO_MANY_ENTRIES)
        )
    }

    // ---- أسباب حد أرشيفية أخرى معروفة تبقى على المسار العام الحرفي (null) ----
    @Test
    fun otherArchiveLimitMessagesKeepTheGenericFallback() {
        assertNull(ArchiveRejections.reasonOf(ArchiveLimitException("total size limit exceeded")))
        assertNull(ArchiveRejections.reasonOf(ArchiveLimitException("cumulative compression ratio limit exceeded")))
        assertNull(ArchiveRejections.reasonOf(ArchiveLimitException("archive preview scan exceeds 1000 bytes")))
        assertNull(ArchiveRejections.reasonOf(RuntimeException("unexpected")))
    }

    // ---- أسباب PPTX غير TOO_LARGE تبقى على التحويل القديم حرفياً حتى مع العلم مفعّلاً ----
    @Test
    fun otherPptxReasonsKeepTheLegacyMappingWithFlagOn() {
        FeatureFlags.archiveRejectionFeedback = true
        assertEquals(R.string.rd_err_encrypted_pptx, PptxLoad.messageFor(PptxException(PptxException.Reason.ENCRYPTED)))
        assertEquals(R.string.rd_err_not_pptx, PptxLoad.messageFor(PptxException(PptxException.Reason.NOT_OOXML)))
        assertEquals(R.string.rd_err_empty, PptxLoad.messageFor(PptxException(PptxException.Reason.EMPTY)))
        assertEquals(R.string.rd_err_corrupt_pptx, PptxLoad.messageFor(PptxException(PptxException.Reason.CORRUPT)))
    }
}
