package org.openfilemanager.app

import org.openfilemanager.core.ConflictDecision
import org.openfilemanager.core.ConflictPolicy
import org.openfilemanager.core.ConflictRequest
import org.openfilemanager.core.EntryFilter
import org.openfilemanager.core.FileOpProgress
import org.openfilemanager.core.OpKind
import org.openfilemanager.core.OperationCancelledException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

internal class ArchiveCoordinator(
    /** السجل والنشر (publishResult/publishOutcome) وأعلام الإلغاء وسجل الاستبدال. */
    private val opsCenter: OpsCenter,
    /** مفتاح الإعدادات «حدود مسترخية»: يُمرَّر لـ SpaceBoundLimits عند كل حساب حدود. */
    private val relaxedLimits: () -> Boolean,
    /** نص رفض الاستخراج عند تعذر قراءة المساحة (R.string.archive_space_unreadable). */
    private val unreadableSpaceMessage: () -> String,
    /** علم مهلة dataSync في الخدمة (@Volatile): يُقرأ عند كل فحص إلغاء. */
    private val timedOut: () -> Boolean,
    /** ضبط تسمية العملية الجارية (currentLabel في الخدمة) قبل بدء التنفيذ. */
    private val setLabel: (String) -> Unit,
    /** إشعار التقدم: نفس مسار المحرك في الخدمة (OpProgressInfo). */
    private val onProgress: (FileOpProgress) -> Unit,
    /** عرض التعارض وانتظار القرار — مسار askConflict المشترك في الخدمة. */
    private val resolveConflict: suspend (ConflictRequest) -> ConflictDecision,
    /** getString(resId) — الموارد النصية بلا تنسيق. */
    private val text: (resId: Int) -> String,
    /** getString(resId, arg) — الموارد النصية بمعامل تنسيق واحد. */
    private val textArg: (resId: Int, arg: Any) -> String,
    /** getQuantityString(pluralRes, quantity, arg1, arg2) — الجموع بمعاملين كما كان الاستدعاء. */
    private val pluralArg: (pluralRes: Int, quantity: Int, arg1: Any, arg2: Any) -> String,
) {

    /**
     * محرك الضغط/الاستخراج فوق عقد OpJournal (كان المساران يدويين
     * بسجل ناقص). مفرد في الحاوية — حدود الاستخراج تُحسب من مساحة الوجهة
     * عبر SpaceBoundLimits.spaceBound الموحّد (N1/D6).
     */
    private val archiveEngine by lazy {
        org.openfilemanager.core.ArchiveOpEngine(
            journal = opsCenter.journal,
            limitsFor = { targetDir ->
                // (N1/D6) المسار الموحّد نفسه الذي تستعمله ArchiveService.
                SpaceBoundLimits.spaceBound(
                    java.io.File(targetDir.toString()), relaxed = relaxedLimits, unreadableMessage = unreadableSpaceMessage
                )
            }
        )
    }

    /** الضغط عبر [ArchiveOpEngine] في. */
    suspend fun runCompress(request: OpRequest.Compress): String {
        val opId = request.opId
        val journal = opsCenter.journal
        val destination = request.targetDir.resolve(request.targetName)
        setLabel(text(R.string.op_kind_compress))
        val stats = archiveEngine.compress(
            sources = request.sources,
            destination = destination,
            opId = opId,
            onProgress = { p ->
                onProgress(
                    FileOpProgress(
                        itemIndex = p.entriesDone, itemCount = p.entriesTotal,
                        bytesDone = p.bytesDone, bytesTotal = p.bytesTotal,
                        current = destination.fileName.toString()
                    )
                )
            },
            isCancelled = { opsCenter.isStopRequested(opId) || opsCenter.queueCancelRequested || timedOut() }
        )
        val message = if (stats.finalPath != null) {
            textArg(R.string.archive_created, BidiText.wrap(destination.fileName.toString()))
        } else {
            text(R.string.archive_create_failed)
        }
        opsCenter.publishResult(message, opId)
        opsCenter.publishOutcome(
            OpOutcome(opId, OpKind.COMPRESS, stats.ok, stats.failed, stats.skipped)
        )
        return message
    }

    /**
     * استخراج الأرشيف مع تطبيق سياسة التعارض نفسها التي تستخدمها عمليات النسخ والنقل.
     * الاستبدال يحفظ الوجهة القديمة باسم احتياطي مؤقت ثم يستعيدها عند الفشل أو الإلغاء.
     */
    suspend fun runExtract(request: OpRequest.Extract, configuredPolicy: ConflictPolicy): String {
        val opId = request.opId
        val archiveFile = request.archive.toFile()
        setLabel(text(R.string.op_kind_extract))
        if (request.selected != null && request.selected.isEmpty()) {
            val message = text(R.string.archive_no_items_selected)
            opsCenter.publishResult(message, opId)
            opsCenter.publishOutcome(OpOutcome(opId, OpKind.EXTRACT, 0, 0, 1))
            return message
        }

        val splitCheck = org.openfilemanager.core.SplitZip.check(request.archive)
        if (splitCheck is org.openfilemanager.core.SplitZipCheck.Incomplete) {
            val message = textArg(
                R.string.archive_missing_parts,
                splitCheck.missing.joinToString(text(R.string.list_separator))
            )
            opsCenter.publishResult(message, opId)
            opsCenter.publishOutcome(OpOutcome(opId, OpKind.EXTRACT, 0, 1, 0))
            return message
        }
        val split = splitCheck is org.openfilemanager.core.SplitZipCheck.Split

        val kind = org.openfilemanager.core.ArchiveFormat.detect(request.archive)
        val singleFileOutput = kind == org.openfilemanager.core.ArchiveFormat.Kind.GZ ||
            kind == org.openfilemanager.core.ArchiveFormat.Kind.XZ
        val naturalName = if (singleFileOutput) {
            org.openfilemanager.core.ArchiveFormat.singleFileName(archiveFile.name)
        } else {
            org.openfilemanager.core.ArchiveFormat.folderName(archiveFile.name)
        }
        val targetDir = request.targetDir
        val naturalTarget = targetDir.resolve(naturalName)
        var finalNameOverride: String? = null
        var backupPath: Path? = null
        var extractionSucceeded = false

        if (!request.placeDirectly && Files.exists(naturalTarget, LinkOption.NOFOLLOW_LINKS)) {
            val decision = when (configuredPolicy) {
                ConflictPolicy.ASK -> resolveConflict(
                    ConflictRequest(
                        source = request.archive,
                        target = naturalTarget,
                        isDirectory = Files.isDirectory(naturalTarget, LinkOption.NOFOLLOW_LINKS)
                    )
                )
                else -> ConflictDecision(configuredPolicy, false)
            }
            when (decision.policy) {
                ConflictPolicy.SKIP, ConflictPolicy.ASK -> {
                    val message = textArg(R.string.archive_destination_skipped, BidiText.wrap(naturalName))
                    opsCenter.publishResult(message, opId)
                    opsCenter.publishOutcome(OpOutcome(opId, OpKind.EXTRACT, 0, 0, 1))
                    return message
                }
                ConflictPolicy.KEEP_BOTH -> {
                    finalNameOverride = uniqueArchiveOutputName(targetDir, naturalName, singleFileOutput)
                }
                ConflictPolicy.REPLACE -> if (request.selected != null &&
                    Files.isDirectory(naturalTarget, LinkOption.NOFOLLOW_LINKS)
                ) {
                    // A partial extraction must never wipe an existing folder: replacing it
                    // would delete every file that is not in the selected entries.
                    finalNameOverride = uniqueArchiveOutputName(targetDir, naturalName, singleFileOutput)
                } else {
                    val backup = uniqueReplaceBackup(naturalTarget)
                    opsCenter.replaceBackups.record(opId, naturalTarget, backup)
                    backupPath = backup
                    moveWithoutReplacement(naturalTarget, backup)
                }
            }
        }

        try {
            val charset = runCatching { Charset.forName(request.charsetName) }.getOrDefault(Charsets.UTF_8)
            val stats = archiveEngine.extract(
                archive = request.archive,
                targetDir = targetDir,
                charset = charset,
                opId = opId,
                split = split,
                onProgress = { entries, bytes ->
                    onProgress(
                        FileOpProgress(
                            itemIndex = entries, itemCount = 0, bytesDone = bytes, bytesTotal = 0L,
                            current = archiveFile.name
                        )
                    )
                },
                checkCancelled = {
                    if (opsCenter.isStopRequested(opId) || opsCenter.queueCancelRequested || timedOut()) {
                        throw OperationCancelledException()
                    }
                },
                // only() لا exactOnly(): مرسلو Extract المحدد (extractHere،
                // لصق «نقل» من أرشيف) يحددون مجلدات — التوسع لكل ما تحتها هو السلوك
                // القديم (EntryFilter.only في ArchiveService) ويجب أن يبقى عبر الخدمة.
                filter = if (request.selected == null) EntryFilter.ALL else EntryFilter.only(request.selected.toSet()),
                finalNameOverride = finalNameOverride,
                placeSelectedDirectly = if (request.placeDirectly) request.selected else null
            )
            extractionSucceeded = stats.finalPath != null
            val finalName = stats.finalPath?.fileName?.toString() ?: naturalName
            val message = if (extractionSucceeded) {
                pluralArg(
                    R.plurals.plurals_archive_extracted, stats.ok, stats.ok, BidiText.wrap(finalName)
                ) + if (stats.skipped > 0) textArg(R.string.archive_extracted_links, stats.skipped) else ""
            } else {
                val reason = if (FeatureFlags.archiveRejectionFeedback)
                    ArchiveRejections.reasonOf(opsCenter.journal.op(opId)?.message) else null
                text(reason?.let(ArchiveRejections::messageRes) ?: R.string.archive_extract_failed)
            }
            opsCenter.publishResult(message, opId)
            opsCenter.publishOutcome(
                OpOutcome(opId, OpKind.EXTRACT, stats.ok, stats.failed, stats.skipped)
            )
            return message
        } finally {
            val backup = backupPath
            if (backup != null) {
                if (extractionSucceeded) {
                    // البديل نُشر بنجاح: النسخة القديمة حُسم أمرها فتُحذف
                    // بأمان، ثم يُختم أمر الاستبدال في سجله الدائم.
                    runCatching { org.openfilemanager.core.AtomicIO.deleteTree(backup) }
                        .onFailure { AppLog.e("ops", "تعذر حذف النسخة الاحتياطية بعد الاستبدال: $backup", it) }
                    opsCenter.replaceBackups.remove(opId)
                } else {
                    runCatching {
                        if (!Files.exists(naturalTarget, LinkOption.NOFOLLOW_LINKS)) {
                            moveWithoutReplacement(backup, naturalTarget)
                            opsCenter.replaceBackups.remove(opId)
                        } else {
                            throw java.io.IOException("target was recreated; backup preserved at $backup")
                        }
                    }.onFailure {
                        // تعذر الحسم بأمان: يبقى الأمر مسجّلاً والنسخة محفوظة
                        // فيحاول استرداد الإقلاع التالي، ويُخبر المستخدم.
                        AppLog.e("ops", "تعذر استعادة الوجهة السابقة من $backup", it)
                        opsCenter.publishResult(text(R.string.replace_backup_kept), opId)
                    }
                }
            }
        }
    }

    /** اسم بديل يحافظ على امتداد الملف المنفرد، ويتجنب أي ملف/مجلد موجود أو رابط رمزي. */
    private fun uniqueArchiveOutputName(parent: Path, baseName: String, preserveExtension: Boolean): String {
        val dot = if (preserveExtension) baseName.lastIndexOf('.').takeIf { it > 0 } else null
        val originalStem = if (dot != null) baseName.substring(0, dot) else baseName
        val extension = if (dot != null) baseName.substring(dot) else ""
        var index = 1
        while (index <= 100_000) {
            val suffix = " ($index)"
            val stemBudget = org.openfilemanager.core.NameRules.MAX_NAME_BYTES -
                suffix.toByteArray(Charsets.UTF_8).size - extension.toByteArray(Charsets.UTF_8).size
            if (stemBudget <= 0) throw java.io.IOException("archive name is too long to make a conflict-safe alternative")
            var stem = originalStem
            while (stem.isNotEmpty() && stem.toByteArray(Charsets.UTF_8).size > stemBudget) stem = stem.dropLast(1)
            val candidate = "$stem$suffix$extension"
            val path = parent.resolve(candidate)
            if (org.openfilemanager.core.NameRules.problem(candidate, strictFat = false) == null &&
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            ) return candidate
            index++
        }
        throw java.io.IOException("could not find an available extraction name")
    }

    /** نقل الوجهة إلى اسم احتياطي فريد دون استبدال أي عنصر آخر. */
    private fun uniqueReplaceBackup(target: Path): Path {
        repeat(8) {
            val candidate = target.resolveSibling(".fm-replace-backup-${UUID.randomUUID()}")
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate
        }
        throw java.io.IOException("could not allocate a safe replacement backup name")
    }

    private fun moveWithoutReplacement(source: Path, target: Path) {
        org.openfilemanager.core.AtomicIO.moveNoReplace(source, target)
    }
}
