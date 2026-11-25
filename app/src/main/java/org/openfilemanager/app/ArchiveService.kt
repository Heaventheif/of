package org.openfilemanager.app

import android.content.Context
import org.openfilemanager.core.ArchiveFormat
import org.openfilemanager.core.ArchiveInspector
import org.openfilemanager.core.ArchiveLimitException
import org.openfilemanager.core.ArchiveListing
import org.openfilemanager.core.EntryFilter
import org.openfilemanager.core.ExtractLimits
import org.openfilemanager.core.SplitZip
import org.openfilemanager.core.SplitZipCheck
import org.openfilemanager.core.UnsafeEntryException
import org.openfilemanager.core.ZipCreator
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

sealed interface ExtractOutcome {
    data class Done(
        val folder: File,
        val skippedLinks: Int,
        /** عدد الملفات المكتوبة فعلاً (الاستخراج الجزئي). */
        val written: Int
    ) : ExtractOutcome
    data class Failed(val message: String) : ExtractOutcome
}

/** نتيجة طلب معاينة محتويات الأرشيف. */
sealed interface PreviewOutcome {
    data class Shown(val listing: ArchiveListing) : PreviewOutcome
    data class Failed(val message: String) : PreviewOutcome
}

/**
 * الإنشاء: ZIP عبر [ZipCreator] في :core (Zip64 عند الحاجة، أسماء UTF-8 بعلم EFS
 * للأسماء العربية، والملفات المضغوطة أصلاً STORED). الاستخراج: ZIP وTAR وTAR.GZ
 * وTAR.XZ وGZ وXZ عبر [org.openfilemanager.core.ArchiveExtractor] وZIP المقسم عبر
 * [SplitZip] (حماية Zip Slip والقنابل والروابط ومجلد مؤقت ثم نقل ذري — كلها في :core
 * ومختبرة)، كاملاً أو العناصر المحددة فقط ([EntryFilter.only]).
 */
class ArchiveService(private val context: Context) {

    /**
     * إنشاء ZIP. [onProgress] و[isCancelled] يمرَّران إلى [ZipCreator] (5):
     * عبر خدمة العمليات يصبح للضغط تقدم حقيقي وإلغاء فوري من الإشعار.
     */
    fun createZip(
        sources: List<File>,
        destination: File,
        onProgress: (org.openfilemanager.core.ZipCreateProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Boolean = try {
        ZipCreator().create(
            sources.map { it.toPath() }, destination.toPath(),
            onProgress = onProgress, isCancelled = isCancelled
        )
        true
    } catch (e: org.openfilemanager.core.OperationCancelledException) {
        throw e // الإلغاء ليس فشلاً: تتعامل معه الخدمة (حالة CANCELLED وتنظيف)
    } catch (e: Exception) {
        AppLog.e("archive", context.getString(R.string.archive_create_failed), e)
        false
    }

    fun extract(
        archive: File,
        destinationDir: File,
        charset: Charset = Charsets.UTF_8,
        limits: ExtractLimits? = null,
        filter: EntryFilter = EntryFilter.ALL,
        onProgress: (entries: Int, bytes: Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {}
    ): ExtractOutcome = try {
        extractResolved(archive, destinationDir, charset, limits, filter, onProgress, checkCancelled, finalNameOverride = null)
    } catch (e: UnsafeEntryException) {
        ExtractOutcome.Failed(context.getString(R.string.archive_unsafe_path))
    } catch (e: ArchiveLimitException) {
        val detail = e.message?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        ExtractOutcome.Failed(context.getString(R.string.archive_limits_exceeded) + detail)
    } catch (e: FileAlreadyExistsException) {
        // «إبقاء الاثنين» (5): الوجهة الموجودة لم تعد فشلاً — مجلد بديل فريد
        // بنمط «الاسم (ن)» يستقبل الاستخراج كما في مديري الملفات.
        runCatching {
            val fallback = uniqueSibling(destinationDir.toPath(), archive.toPath())
            extractResolved(archive, destinationDir, charset, limits, filter, onProgress, checkCancelled, fallback.fileName.toString())
        }.getOrElse {
            ExtractOutcome.Failed(context.getString(R.string.archive_destination_exists))
        }
    } catch (e: IOException) {
        ExtractOutcome.Failed(e.message ?: context.getString(R.string.archive_extract_failed))
    }

    /**
     * مسار استخراج واحد للحالة العادية وفرع «إبقاء الاثنين»: الأرشيف المقسّم يمر دائماً
     * عبر [SplitZip] (يدمج الأجزاء) مع التقدم والإلغاء، وغير المقسّم عبر ArchiveExtractor.
     */
    private fun extractResolved(
        archive: File,
        destinationDir: File,
        charset: Charset,
        limits: ExtractLimits?,
        filter: EntryFilter,
        onProgress: (entries: Int, bytes: Long) -> Unit,
        checkCancelled: () -> Unit,
        finalNameOverride: String?
    ): ExtractOutcome {
        val effectiveLimits = limits ?: spaceBoundLimits(destinationDir)
        return when (val split = SplitZip.check(archive.toPath())) {
            is SplitZipCheck.Incomplete ->
                ExtractOutcome.Failed(context.getString(R.string.archive_missing_parts, split.missing.joinToString(context.getString(R.string.list_separator))))
            is SplitZipCheck.Split -> {
                val report = SplitZip.extract(
                    archive.toPath(), destinationDir.toPath(), effectiveLimits, charset,
                    onProgress = onProgress, checkCancelled = checkCancelled, filter = filter,
                    finalNameOverride = finalNameOverride
                )
                ExtractOutcome.Done(report.finalPath.toFile(), report.skipped.size, report.written)
            }
            SplitZipCheck.NotSplit -> {
                val report = org.openfilemanager.core.ArchiveExtractor(effectiveLimits, charset)
                    .extract(
                        archive.toPath(), destinationDir.toPath(),
                        onProgress = onProgress, checkCancelled = checkCancelled, filter = filter,
                        finalNameOverride = finalNameOverride
                    )
                ExtractOutcome.Done(report.finalPath.toFile(), report.skipped.size, report.written)
            }
        }
    }

    /**
     * حدود الاستخراج المرتبطة بالمساحة المتاحة. إن تعذرت قراءتها (أو كانت صفراً)
     * يُرفض الاستخراج بدل إلغاء الحد الكلي (كان يرجع إلى Long.MAX_VALUE).
     * مجلد الوجهة قد لا يكون موجوداً بعد (يُنشأ عند الاستخراج) فيُسأل أقرب أب موجود.
     */
    private fun spaceBoundLimits(destinationDir: File): ExtractLimits {
        var probe: File? = destinationDir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        val usable = probe?.let { runCatching { it.usableSpace }.getOrNull() }
        if (usable == null || usable <= 0L) {
            throw ArchiveLimitException(context.getString(R.string.archive_space_unreadable))
        }
        // (مراجعة 0.8.6-#3) سقف مخفَّف اختياري من الإعدادات للملفات المشروعة شديدة الانضغاط.
        return if (context.appContainer.settings.archiveRelaxedLimits) ExtractLimits.relaxed(usable)
        else ExtractLimits(maxTotalBytes = usable)
    }

    /** اسم بديل فريد للمجلد الذي سيستقبل الاستخراج: «الاسم (n)». */
    private fun uniqueSibling(destinationDir: Path, archive: Path): Path {
        val finalName = ArchiveFormat.folderName(archive.fileName.toString())
        var candidate = destinationDir.resolve(finalName)
        var n = 1
        val dot = finalName.lastIndexOf('.').takeIf { it > 0 }
        val stem = if (dot != null) finalName.substring(0, dot) else finalName
        val ext = if (dot != null) finalName.substring(dot) else ""
        while (Files.exists(candidate)) {
            candidate = destinationDir.resolve("$stem ($n)$ext")
            n++
        }
        return candidate
    }

    /**
     * معاينة قبل الاستخراج: قراءة العناوين فقط بلا فك ضغط ولا كتابة في
     * الوجهة. الأرشيف المقسم يُدمج في مجلد مؤقت ([stagingDir] من كاش التطبيق) إن كان
     * أصغر من سقف [SPLIT_PREVIEW_LIMIT]، وإلا تُرفض المعاينة (الاستخراج الكامل متاح).
     */
    fun preview(archive: File, charset: Charset, stagingDir: Path): PreviewOutcome = try {
        when (val split = SplitZip.check(archive.toPath())) {
            is SplitZipCheck.Incomplete ->
                PreviewOutcome.Failed(context.getString(R.string.archive_missing_parts, split.missing.joinToString(context.getString(R.string.list_separator))))
            is SplitZipCheck.Split -> {
                val total = split.parts.sumOf { Files.size(it) }
                if (total > SPLIT_PREVIEW_LIMIT) {
                    PreviewOutcome.Failed(context.getString(R.string.archive_preview_split_too_large))
                } else {
                    val staging = stagingDir.resolve(".fm-preview-" + System.nanoTime())
                    try {
                        Files.createDirectories(staging)
                        val merged = staging.resolve(archive.name)
                        Files.newOutputStream(merged).use { out ->
                            SplitZip.mergeTo(split.parts, out)
                        }
                        PreviewOutcome.Shown(ArchiveInspector.list(merged, charset, maxEntries = PREVIEW_MAX_ENTRIES))
                    } finally {
                        runCatching { org.openfilemanager.core.AtomicIO.deleteTree(staging) }
                    }
                }
            }
            SplitZipCheck.NotSplit ->
                PreviewOutcome.Shown(ArchiveInspector.list(archive.toPath(), charset, maxEntries = PREVIEW_MAX_ENTRIES))
        }
    } catch (e: UnsafeEntryException) {
        PreviewOutcome.Failed(context.getString(R.string.archive_preview_unsafe))
    } catch (e: ArchiveLimitException) {
        PreviewOutcome.Failed(context.getString(R.string.archive_preview_limits))
    } catch (e: IOException) {
        PreviewOutcome.Failed(e.message ?: context.getString(R.string.archive_preview_failed))
    }

    private companion object {
        /** سقف دمج أجزاء ZIP المقسم لأجل المعاينة فقط: 512MB. */
        const val SPLIT_PREVIEW_LIMIT = 512L * 1024 * 1024
        /** سقف معاينة موحد مع صفحات اختيار العناصر، دون تحميل فهرس غير محدود. */
        const val PREVIEW_MAX_ENTRIES = 20_000
    }
}
