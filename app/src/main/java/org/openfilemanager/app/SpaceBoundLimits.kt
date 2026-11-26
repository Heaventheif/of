package org.openfilemanager.app

import org.openfilemanager.core.ArchiveLimitException
import org.openfilemanager.core.ExtractLimits
import java.io.File

object SpaceBoundLimits {

    fun forDestination(
        destinationDir: File,
        relaxed: Boolean,
        unreadableMessage: String,
        usableSpaceOf: (File) -> Long? = { f -> runCatching { f.usableSpace }.getOrNull() }
    ): ExtractLimits {
        var probe: File? = destinationDir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        val usable = probe?.let(usableSpaceOf)
        if (usable == null || usable <= 0L) {
            throw ArchiveLimitException(unreadableMessage)
        }
        return if (relaxed) ExtractLimits.relaxed(usable)
        else ExtractLimits(maxTotalBytes = usable)
    }

    /**
     * (N1/D6) مسار موحّد للمقدمين: الاستدعاء نفسه الذي كانت تلفّه النسختان
     * المكررتان في ArchiveService وArchiveCoordinator — الإعداد والرسالة
     * يُقرآن لحظة الحساب (لا عند البناء) كما كان سلوك المسارين.
     */
    fun spaceBound(
        destinationDir: File,
        relaxed: () -> Boolean,
        unreadableMessage: () -> String,
        usableSpaceOf: (File) -> Long? = { f -> runCatching { f.usableSpace }.getOrNull() }
    ): ExtractLimits =
        forDestination(destinationDir, relaxed(), unreadableMessage(), usableSpaceOf)
}
