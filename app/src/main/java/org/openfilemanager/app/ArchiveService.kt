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

/** الإنشاء: ZIP عبر [ZipCreator] في :core (Zip64 عند الحاجة، أسماء UTF-8 بعلم EFS للأسماء العربية، والملفات المضغوطة أصلاً STORED). */
class ArchiveService(private val context: Context) {

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
        val specific = if (FeatureFlags.archiveRejectionFeedback) ArchiveRejections.reasonOf(e) else null
        if (specific != null) {
            ExtractOutcome.Failed(context.getString(ArchiveRejections.messageRes(specific)))
        } else {
            val detail = e.message?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            ExtractOutcome.Failed(context.getString(R.string.archive_limits_exceeded) + detail)
        }
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
        val effectiveLimits = limits ?: SpaceBoundLimits.spaceBound(
            destinationDir,
            relaxed = { context.appContainer.settings.archiveRelaxedLimits },
            unreadableMessage = { context.getString(R.string.archive_space_unreadable) }
        )
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

    /** اسم بديل فريد للمجلد الذي سيستقبل الاستخراج: «الاسم (n)». */
    private fun uniqueSibling(destinationDir: Path, archive: Path): Path {
        val finalName = ArchiveFormat.folderName(archive.fileName.toString())
        // توحيد الاسم الفريد عبر NameRules.uniqueName — كان مكرراً.
        val unique = org.openfilemanager.core.NameRules.uniqueName(finalName) { c -> Files.exists(destinationDir.resolve(c)) }
        return destinationDir.resolve(unique)
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
        val specific = if (FeatureFlags.archiveRejectionFeedback) ArchiveRejections.reasonOf(e) else null
        PreviewOutcome.Failed(
            context.getString(specific?.let(ArchiveRejections::messageRes) ?: R.string.archive_preview_limits)
        )
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
