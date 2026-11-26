package org.openfilemanager.app

import org.openfilemanager.core.ArchiveLimitException
import org.openfilemanager.core.pptx.PptxException

internal object ArchiveRejections {

    enum class Reason { TOO_MANY_ENTRIES, CENTRAL_DIRECTORY_TOO_LARGE, DECODED_OVER_BUDGET }

    /** بادئة رسالة حد العناصر في :core (ثابتة، لا مسارات فيها). */
    private const val TOO_MANY_ENTRIES_PREFIX = "too many entries"

    private const val CENTRAL_DIRECTORY_PREFIX = "central directory exceeds"

    /** السبب المحدد من الاستثناء؛ null = لا سبب معروف (النص العام كما هو). */
    fun reasonOf(e: Throwable): Reason? = when {
        e is PptxException && e.reason == PptxException.Reason.TOO_LARGE -> Reason.DECODED_OVER_BUDGET
        e is ArchiveLimitException -> reasonOf(e.message)
        else -> null
    }

    /**
     * السبب المحدد من رسالة عذر خام — رسالة :core الإنجليزية الثابتة التي دوّنها
     * المحرك في سجل العمليات عند رفض الاستخراج (مسار الخدمة لا يرى الاستثناء).
     */
    fun reasonOf(message: String?): Reason? = when {
        message?.startsWith(TOO_MANY_ENTRIES_PREFIX) == true -> Reason.TOO_MANY_ENTRIES
        message?.startsWith(CENTRAL_DIRECTORY_PREFIX) == true -> Reason.CENTRAL_DIRECTORY_TOO_LARGE
        else -> null
    }

    /** معرف النص المحدد للسبب — نص ثابت بلا معاملات فلا قيمة من الاستثناء تُقحم. */
    fun messageRes(reason: Reason): Int = when (reason) {
        Reason.TOO_MANY_ENTRIES -> R.string.archive_err_too_many_entries
        Reason.CENTRAL_DIRECTORY_TOO_LARGE -> R.string.archive_err_central_directory_too_large
        Reason.DECODED_OVER_BUDGET -> R.string.rd_err_too_large
    }
}
