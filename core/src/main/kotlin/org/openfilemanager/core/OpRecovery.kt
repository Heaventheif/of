package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/** نتيجة استرداد تسمية جماعية انقطعت. */
data class RenameRecovery(
    /** عناصر وصلت اسمها النهائي. */
    val finished: Int,
    /** عناصر أُعيدت لاسمها الأصلي. */
    val restored: Int,
    /** عناصر لم تُمس (كانت منتهية أصلاً أو لم تُلمس عند الإلغاء). */
    val leftAsIs: Int,
    val errors: List<String>
)

/** استرداد العمليات الطويلة عند إقلاع التطبيق: 1. */
object OpRecovery {

    /** العمليات التي انقطعت بموت العملية (لا سطر إنهاء لها). */
    fun interrupted(journal: OpJournal): List<OpRecord> =
        journal.ops().filter { it.state == OpState.RUNNING }

    /** العمليات الجديرة بفحص الانقطاع عند الإقلاع/إعادة إنشاء الواجهة. */
    fun recoverableOps(
        ops: List<OpRecord>,
        activeOpIds: Set<String> = emptySet(),
        serviceBusy: Boolean = false
    ): List<OpRecord> {
        if (serviceBusy) return emptyList()
        return ops.filter {
            (it.state == OpState.RUNNING || it.state == OpState.INTERRUPTED) &&
                it.id !in activeOpIds && isResumable(it)
        }
    }

    /** الضغط والاستخراج لا يُستأنفان من سجل العناصر. */
    fun isResumable(op: OpRecord): Boolean = op.kind != OpKind.COMPRESS && op.kind != OpKind.EXTRACT

    /**
     * ينظّف ويُنهي (CANCELLED) عمليات الضغط/الاستخراج المنقطعة غير القابلة للاستئناف.
     * يعيد عددها. لا يعمل أثناء عمل الخدمة ولا على عمليات حية ([activeOpIds]).
     */
    fun abandonUnresumable(
        journal: OpJournal,
        ops: List<OpRecord>,
        activeOpIds: Set<String> = emptySet(),
        serviceBusy: Boolean = false,
        message: String = CoreOpCode.OP_INTERRUPTED
    ): Int {
        if (serviceBusy) return 0
        var n = 0
        for (op in ops) {
            if (op.state != OpState.RUNNING && op.state != OpState.INTERRUPTED) continue
            if (op.id in activeOpIds || isResumable(op)) continue
            runCatching { cleanupTemps(journal, op) }
            // مؤقتات الضغط/الاستخراج (.fm-extract-/.fm-merge-/.fm-tmp-zip-) في مجلد
            // الوجهة (للاستخراج: الوجهة نفسها مجلد) ومجلد أبيها؛ العملية ميتة فلا حد عمر.
            runCatching {
                val dirs = HashSet<Path>()
                for (item in journal.items(op.id)) {
                    val dst = Paths.get(item.dst)
                    if (op.kind == OpKind.EXTRACT) dirs.add(dst)
                    dst.parent?.let { dirs.add(it) }
                }
                for (dir in dirs) {
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) continue
                    for (prefix in ALL_TEMP_PREFIXES) {
                        Files.newDirectoryStream(dir, "$prefix*").use { st ->
                            for (tmp in st) runCatching { AtomicIO.deleteTree(tmp) }
                        }
                    }
                }
            }
            journal.finishOp(op.id, OpState.CANCELLED, 0, 0, 1, message)
            n++
        }
        return n
    }

    /** يحذف الملفات المؤقتة المتروكة من عملية انقطعت. */
    fun cleanupTemps(
        journal: OpJournal,
        op: OpRecord,
        parsePath: (String) -> Path = { Paths.get(it) },
        minAgeMs: Long = 0L
    ): Int {
        if (op.state != OpState.RUNNING && op.state != OpState.INTERRUPTED) return 0
        val cutoff = System.currentTimeMillis() - minAgeMs
        val parents = HashSet<Path>()
        for (item in journal.items(op.id)) {
            if (item.state == ItemState.DONE || item.state == ItemState.SKIPPED) continue
            if (item.itemKind != OpItemKind.FILE) continue
            parsePath(item.dst).parent?.let { parents.add(it) }
            parsePath(item.src).parent?.let { parents.add(it) }
        }
        var removed = 0
        for (parent in parents) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) continue
            for (prefix in LEFTOVER_TEMP_PREFIXES) {
                Files.newDirectoryStream(parent, "$prefix*").use { stream ->
                    for (tmp in stream) {
                        // حد عمر اختياري: مؤقت حديث قد يخص عملية حية أخرى في المجلد نفسه.
                        if (minAgeMs > 0 && runCatching {
                                Files.getLastModifiedTime(tmp).toMillis() > cutoff
                            }.getOrDefault(false)
                        ) continue
                        if (runCatching { AtomicIO.deleteTree(tmp); true }.getOrDefault(false)) removed++
                    }
                }
            }
        }
        return removed
    }

    /** بادئات مؤقتات العمليات التي قد تُترك بموت العملية (منظفات التمهيد).
     *  لا تشمل `.fm-replace-backup-` — انظر تعليق [cleanupTemps]. */
    val LEFTOVER_TEMP_PREFIXES = arrayOf(".fm-tmp-", ".fm-replaced-")

    /** منظف تمهيد: يمسح مؤقتات العمليات القديمة من مجلدات وجهاتها — `.fm-tmp-*` و`.fm-replaced-*` (تنظيف الانقطاع أعلاه يغطيها أيضاً)، و`.fm-extract-*`. */
    fun cleanupStaleTemps(
        journal: OpJournal,
        minAgeMs: Long = STALE_TEMP_MIN_AGE_MS,
        nowMillis: Long = System.currentTimeMillis(),
        parsePath: (String) -> Path = { Paths.get(it) }
    ): Int {
        val deadline = nowMillis - minAgeMs
        val parents = HashSet<Path>()
        for (op in journal.ops()) {
            for (item in journal.items(op.id)) {
                parsePath(item.dst).parent?.let { parents.add(it) }
            }
        }
        var removed = 0
        for (parent in parents) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) continue
            for (prefix in ALL_TEMP_PREFIXES) {
                Files.newDirectoryStream(parent, "$prefix*").use { stream ->
                    for (tmp in stream) {
                        val lastModified = runCatching {
                            Files.getLastModifiedTime(tmp, LinkOption.NOFOLLOW_LINKS).toMillis()
                        }.getOrDefault(Long.MAX_VALUE)
                        if (lastModified >= deadline) continue
                        if (runCatching { AtomicIO.deleteTree(tmp); true }.getOrDefault(false)) {
                            removed++
                        }
                    }
                }
            }
        }
        return removed
    }

    /** كل بادئات مؤقتات العمليات (المنظف العام) — بلا نسخ الاستبدال الاحتياطية. */
    val ALL_TEMP_PREFIXES = arrayOf(".fm-tmp-", ".fm-replaced-", ".fm-extract-", ".fm-merge-", ".fm-tmp-zip-")

    /** حد أمان عمر المؤقت القابل للحذف: ساعة واحدة. */
    const val STALE_TEMP_MIN_AGE_MS = 60L * 60 * 1000

    /** يسترد تسمية جماعية انقطعت (سجل [JournalRenameAdapter]): عنصر DONE وصل نهائيه؛ */
    fun recoverRename(
        journal: OpJournal,
        opId: String,
        complete: Boolean,
        parsePath: (String) -> Path = { Paths.get(it) }
    ): RenameRecovery {
        val record = journal.op(opId)
            ?: return RenameRecovery(0, 0, 0, listOf(CoreOpCode.message(CoreOpCode.NO_OPERATION, opId)))
        if (record.kind != OpKind.RENAME) return RenameRecovery(0, 0, 0, listOf(CoreOpCode.NOT_RENAME_OP))

        var finished = 0
        var restored = 0
        var leftAsIs = 0
        val errors = ArrayList<String>()

        for (item in journal.items(opId)) {
            val from = parsePath(item.src)
            val temp = parsePath(item.extra)
            val to = parsePath(item.dst)
            val tempExists = Files.exists(temp, LinkOption.NOFOLLOW_LINKS)
            val fromExists = Files.exists(from, LinkOption.NOFOLLOW_LINKS)
            val toExists = Files.exists(to, LinkOption.NOFOLLOW_LINKS)

            try {
                when {
                    item.state == ItemState.DONE || toExists && !tempExists && !fromExists ->
                        leftAsIs++

                    tempExists -> {
                        if (complete && !toExists) {
                            AtomicIO.moveNoReplace(temp, to)
                            journal.updateItem(opId, item.index, ItemState.DONE)
                            finished++
                        } else {
                            AtomicIO.moveNoReplace(temp, from)
                            journal.updateItem(
                                opId, item.index, ItemState.SKIPPED,
                                error = if (toExists) CoreOpCode.DESTINATION_EXISTS_RESTORED else CoreOpCode.CANCELLED
                            )
                            restored++
                        }
                    }

                    fromExists ->
                        if (complete && !toExists) {
                            AtomicIO.moveNoReplace(from, temp)
                            AtomicIO.moveNoReplace(temp, to)
                            journal.updateItem(opId, item.index, ItemState.DONE)
                            finished++
                        } else if (toExists) {
                            journal.updateItem(opId, item.index, ItemState.SKIPPED, error = CoreOpCode.DESTINATION_EXISTS)
                            leftAsIs++
                        } else {
                            leftAsIs++
                        }

                    else -> {
                        journal.updateItem(opId, item.index, ItemState.SKIPPED, error = CoreOpCode.SOURCE_MISSING)
                        leftAsIs++
                    }
                }
            } catch (e: IOException) {
                journal.updateItem(opId, item.index, ItemState.FAILED, error = e.message ?: "")
                errors += "${from.fileName}: ${e.message}"
            }
        }

        journal.finishOp(
            opId, OpState.FINISHED, finished, errors.size, leftAsIs,
            if (complete) CoreOpCode.message(CoreOpCode.RECOVERY_SUMMARY, finished, restored)
            else CoreOpCode.message(CoreOpCode.UNDO_SUMMARY, restored)
        )
        return RenameRecovery(finished, restored, leftAsIs, errors)
    }
}
