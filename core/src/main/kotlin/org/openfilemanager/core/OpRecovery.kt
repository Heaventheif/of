package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

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

/**
 * استرداد العمليات الطويلة عند إقلاع التطبيق:
 * 1. العمليات بحالة RUNNING هي التي ماتت مع العملية (الخدمة تكتب إنهاءها دائماً).
 * 2. تُحذف الملفات المؤقتة `.fm-tmp-*` المتروكة في مجلدات الوجهة.
 * 3. يعرض المستخدم القرار: إكمال (استئناف) أو إلغاء؛ والتسمية الجماعية لها مسارها الخاص هنا.
 *
 * [parsePath] يفك مسارات السجل؛ الإنتاج يستخدم الافتراضي والاختبارات تحقن Jimfs
 * (نفس الحقنة في [FileOpEngine]).
 */
object OpRecovery {

    /** العمليات التي انقطعت بموت العملية (لا سطر إنهاء لها). */
    fun interrupted(journal: OpJournal): List<OpRecord> =
        journal.ops().filter { it.state == OpState.RUNNING }

    fun isInterrupted(journal: OpJournal, op: OpRecord): Boolean = op.state == OpState.RUNNING

    /**
     * العمليات الجديرة بفحص الانقطاع عند الإقلاع/إعادة إنشاء الواجهة:
     * RUNNING/INTERRUPTED فقط، وباستثناء معرّفات العمليات التي تُنفَّذ الآن في
     * هذه العملية الحية ([activeOpIds]) وعند وجود عمل خدمة نشط كلياً
     * ([serviceBusy] = true) — سجل RUNNING لعملية يجري تنفيذها ليس «منقطعاً»،
     * وإعلانه كذلك كان يعرض حوار الاسترداد زوراً أثناء إعادة إنشاء النشاط
     * (تدوير/تغيير لغة/عودة للمهمة) وزر «إلغاء» فيه يحذف مؤقتات العملية الحية.
     */
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

    /**
     * الضغط والاستخراج لا يُستأنفان من سجل العناصر: عنصرهما الوحيد (FILE) يمثل
     * «المصدر ← الوجهة» لتنظيف المؤقتات فقط، واستئنافه عبر محرك النسخ كان ينسخ
     * أول مصدر فوق مسار الـ zip أو ملف الأرشيف فوق مجلد الوجهة. يُلغيان ويُنظَّف
     * مؤقتاهما ([abandonUnresumable]).
     */
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
        message: String = "انقطعت بموت العملية"
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

    /** يعلّم العملية INTERRUPTED حتى لا تُكشف مرة أخرى عند الإقلاع التالي. */
    fun markInterrupted(journal: OpJournal, op: OpRecord) {
        journal.finishOp(op.id, OpState.INTERRUPTED, op.ok, op.failed, op.skipped, "انقطعت بموت العملية")
    }

    /**
     * يحذف الملفات المؤقتة المتروكة من عملية انقطعت. `.fm-tmp-*` (نسخ AtomICIO)
     * و`.fm-replaced-*` (إزاحة الهدف عند الاستبدال) لا يعرف اسمها العشوائي
     * السجل فيُمسح كل ما بدأ بالبادئة في مجلد وجهة كل عنصر لم يكتمل.
     * [AtomicIO] ينظف مؤقته عند الفشل الطبيعي، فهذا المسح لموت العملية
     * المفاجئ فقط. إعادة الترتيب من `.fm-replaced-*` إلى هدفها المفقود خارج
     * النطاق عمداً (لا نعرف إن كانت النسخة الجديدة اكتملت) — يُحذف فحسب.
     * يعيد عدد ما حُذف.
     *
     * نسخ استبدال الأرشيف `.fm-replace-backup-*` **مستثناة هنا عمداً**:
     * هي بيانات مستخدم أصيلة (المجلد القديم نفسه)، ومصيرها يحسمه
     * [ReplaceBackupRecovery.reconcile] وحده وفق سجل أوامر الاستبدال الدائم
     * وسجل العمليات — لا منظّف المؤقتات العامة.
     */
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

    /** بادئات مؤقتات العمليات التي قد تُترك بموت العملية (منظفات التمهيد 3.3).
     *  لا تشمل `.fm-replace-backup-` — انظر تعليق [cleanupTemps]. */
    val LEFTOVER_TEMP_PREFIXES = arrayOf(".fm-tmp-", ".fm-replaced-")

    /**
     * منظف تمهيد: يمسح مؤقتات العمليات القديمة من مجلدات وجهاتها — `.fm-tmp-*`
     * و`.fm-replaced-*` (تنظيف الانقطاع أعلاه يغطيها أيضاً)، و`.fm-extract-*`
     * و`.fm-merge-*` و`.fm-tmp-zip-*` (استخراج/دمج/ضغط الأرشيف، لا مجلد وجهة
     * لها في سجل عناصر FILE فلا يصل إليها cleanupTemps).
     *
     * `.fm-replace-backup-*` **ليست مؤقتة** — هي المجلد القديم نفسه عند
     * استبداله باستخراج أرشيف، ويحسم مصيرها [ReplaceBackupRecovery] وحده.
     * حذفها هنا كان يمحو بيانات المستخدم عند موت العملية وسط استبدال.
     *
     * يشمل فقط مجلدات سجلات العمليات (dirs of journaled ops) — لا يُمسح مجلد
     * يتصفحه المستخدم ولا أي مسار آخر، احتياطاً من حذف ملفات مستخدم تحمل
     * الاسم بالصدفة.
     *
     * [minAgeMs] حد أمان: لا يُحذف مؤقت أحدث منه — عملية خدمة قد تكون بدأت
     * للتو (فحص الانقطاع 1.2 يستثنيها من الأساس، وهذا طبقة ثانية).
     * [nowMillis] يُحقن للاختبار.
     *
     * @return عدد ما حُذف (للتسجيل فقط).
     */
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

    /**
     * يسترد تسمية جماعية انقطعت (سجل [JournalRenameAdapter]):
     * عنصر DONE وصل نهائيه؛ PENDING لم يُلمس؛ RUNNING وصل الاسم المؤقت.
     * - [complete] = true: المؤقت يذهب لاسمه النهائي، والـPENDING يُنفذ (مرحلتان)،
     *   فإن كانت الوجهة محجوزة يُعاد الاسم الأصلي ولا يُكتب فوق شيء أبداً.
     * - [complete] = false: كل مؤقت يعود لاسمه الأصلي، والـPENDING يبقى كما كان.
     */
    fun recoverRename(
        journal: OpJournal,
        opId: String,
        complete: Boolean,
        parsePath: (String) -> Path = { Paths.get(it) }
    ): RenameRecovery {
        val record = journal.op(opId)
            ?: return RenameRecovery(0, 0, 0, listOf("لا توجد عملية بهذا المعرف: $opId"))
        if (record.kind != OpKind.RENAME) return RenameRecovery(0, 0, 0, listOf("ليست عملية تسمية"))

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
                            Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE)
                            journal.updateItem(opId, item.index, ItemState.DONE)
                            finished++
                        } else {
                            Files.move(temp, from)
                            journal.updateItem(
                                opId, item.index, ItemState.SKIPPED,
                                error = if (toExists) "الوجهة موجودة؛ أُعيد الاسم الأصلي" else "أُلغيت"
                            )
                            restored++
                        }
                    }

                    fromExists ->
                        if (complete && !toExists) {
                            Files.move(from, temp)
                            Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE)
                            journal.updateItem(opId, item.index, ItemState.DONE)
                            finished++
                        } else if (toExists) {
                            journal.updateItem(opId, item.index, ItemState.SKIPPED, error = "الوجهة موجودة")
                            leftAsIs++
                        } else {
                            leftAsIs++
                        }

                    else -> {
                        journal.updateItem(opId, item.index, ItemState.SKIPPED, error = "المصدر غير موجود")
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
            if (complete) "استرداد التسمية: أكمل $finished، أعاد $restored"
            else "إلغاء التسمية: أعاد $restored لاسمه الأصلي"
        )
        return RenameRecovery(finished, restored, leftAsIs, errors)
    }
}
