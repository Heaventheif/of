package org.openfilemanager.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/** سجل دائم للنسخ الاحتياطية التي تحتوي بيانات المستخدم عند استبدال مجلد الوجهة أثناء استخراج أرشيف بسياسة REPLACE. */
class ReplaceBackupStore(private val dir: Path) {

    /** أمر استبدال مسجّل: الأصل [original] نُقل إلى [backup] بانتظار نتيجة الاستخراج. */
    data class Record(
        val opId: String,
        val original: String,
        val backup: String,
        val createdAt: Long
    )

    init {
        Files.createDirectories(dir)
    }

    private fun file(opId: String): Path {
        require(opId.all { it.isLetterOrDigit() || it == '-' }) { "bad op id" }
        return dir.resolve("replace-$opId.tsv")
    }

    /**
     * تسجيل الأمر قبل نقل الأصل. الكتابة ذرية ومُدوَّمة (force) حتى تنجو
     * من موت العملية وانقطاع الطاقة مباشرة بعد العودة.
     */
    fun record(opId: String, original: Path, backup: Path) {
        val f = file(opId)
        if (Files.exists(f)) throw java.nio.file.FileAlreadyExistsException(f.toString())
        val line = listOf(
            "V1", Tsv.encode(opId), Tsv.encode(original.toString()),
            Tsv.encode(backup.toString()), System.currentTimeMillis().toString()
        ).joinToString("\t") + "\n"
        val tmp = dir.resolve(".fm-journal-${java.util.UUID.randomUUID()}.tmp")
        try {
            java.nio.channels.FileChannel.open(
                tmp, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE
            ).use { ch ->
                ch.write(java.nio.ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8)))
                ch.force(true)
            }
            Files.move(tmp, f)
            AtomicIO.fsyncDirectory(dir)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }

    fun get(opId: String): Record? {
        val f = file(opId)
        if (!Files.exists(f)) return null
        return parse(f)
    }

    fun all(): List<Record> {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.newDirectoryStream(dir, "replace-*.tsv").use { stream ->
            return stream.mapNotNull { parse(it) }
        }
    }

    /** إنهاء أمر الاستبدال: يُستدعى فقط بعد حسم مصير النسخة الاحتياطية. */
    fun remove(opId: String) {
        runCatching { Files.deleteIfExists(file(opId)) }
        AtomicIO.fsyncDirectory(dir)
    }

    private fun parse(f: Path): Record? = runCatching {
        val cols = String(Files.readAllBytes(f), StandardCharsets.UTF_8).trimEnd('\n').split('\t')
        if (cols.getOrNull(0) != "V1") return@runCatching null
        Record(
            opId = Tsv.decode(cols[1]),
            original = Tsv.decode(cols.getOrElse(2) { "" }),
            backup = Tsv.decode(cols.getOrElse(3) { "" }),
            createdAt = cols.getOrElse(4) { "0" }.toLongOrNull() ?: 0
        )
    }.getOrNull()
}

/** تسوية أوامر الاستبدال المعلّقة عند الإقلاع. */
object ReplaceBackupRecovery {

    /** نتيجة التسوية لكل الأوامر (للسجل وعرض المستخدم). */
    data class Outcome(
        /** أوامر استُعيد أصلها من النسخة الاحتياطية (فشل/انقطاع الاستخراج). */
        val restored: List<String>,
        /** أوامر حُذفت نسختها الاحتياطية بعد ثبوت نجاح البديل المنشور. */
        val removed: List<String>,
        /** أوامر تعذر حسمها بأمان: النسخة الاحتياطية محفوظة كما هي. */
        val kept: List<String>,
        /** أوصاف المشكلات (مسارات مختصرة عبر PathRedactor عند الطباعة). */
        val errors: List<String>
    )

    /**
     * @param activeOpIds عمليات تُنفَّذ الآن في هذه العملية الحية: تُترك
     * لخدمتها ولا يُمس لها شيء.
     * @param parsePath يفك مسارات السجل (الاختبارات تحقن ما تحتاجه).
     */
    fun reconcile(
        store: ReplaceBackupStore,
        journal: OpJournal,
        activeOpIds: Set<String> = emptySet(),
        parsePath: (String) -> Path = { Paths.get(it) }
    ): Outcome {
        val restored = ArrayList<String>()
        val removed = ArrayList<String>()
        val kept = ArrayList<String>()
        val errors = ArrayList<String>()

        for (rec in store.all()) {
            if (rec.opId in activeOpIds) continue
            try {
                reconcileOne(rec, store, journal, parsePath, restored, removed, kept, errors)
            } catch (e: IOException) {
                kept += rec.opId
                errors += "${rec.opId}: ${e.message}"
            }
        }
        return Outcome(restored, removed, kept, errors)
    }

    private fun reconcileOne(
        rec: ReplaceBackupStore.Record,
        store: ReplaceBackupStore,
        journal: OpJournal,
        parsePath: (String) -> Path,
        restored: MutableList<String>,
        removed: MutableList<String>,
        kept: MutableList<String>,
        errors: MutableList<String>
    ) {
        val original = parsePath(rec.original)
        val backup = parsePath(rec.backup)
        val originalExists = Files.exists(original, LinkOption.NOFOLLOW_LINKS)
        val backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
        val op = runCatching { journal.op(rec.opId) }.getOrNull()

        if (!backupExists) {
            // النقل إلى النسخة الاحتياطية لم يحدث (سُجّل الأمر ثم ماتت العملية
            // قبل move)، أو حُسم الأمر في تشغيل سابق ولم يُحذف السجل.
            if (!originalExists) errors += CoreOpCode.BOTH_SOURCE_AND_BACKUP_MISSING
            store.remove(rec.opId)
            return
        }

        when {
            op == null -> {
                // لا سجل عملية. إن كانت الوجهة غائبة فالاستخراج لم ينشر بديلاً قط (النشر يسبق دائماً إنهاء السجل، والسجل يُنشأ قبل أي كتابة): إرجاع الأصل آمن.
                if (originalExists) {
                    keep(rec, kept, errors, CoreOpCode.NO_EXTRACT_LOG)
                } else {
                    AtomicIO.moveNoReplace(backup, original)
                    store.remove(rec.opId)
                    restored += rec.opId
                }
            }

            op.state == OpState.FINISHED -> {
                // الاستخراج اكتمل ونُشر البديل (النشر يسبق سطر الإنهاء):
                // الأصل الموجود الآن هو المحتوى الجديد، فالنسخة القديمة
                // حُسم أمرها ويُحذفها بأمان.
                if (originalExists) {
                    AtomicIO.deleteTree(backup)
                    store.remove(rec.opId)
                    removed += rec.opId
                } else {
                    keep(rec, kept, errors, CoreOpCode.FINISHED_DEST_MISSING)
                }
            }

            else -> {
                // RUNNING/INTERRUPTED/CANCELLED/FAILED: الاستخراج لم يكتمل
                // ولا بديل منشوراً — الأصل يجب أن يعود من النسخة الاحتياطية.
                if (originalExists) {
                    // وجهة موجودة مع عملية غير مكتملة: لا نكتب فوقها ولا
                    // نحذف النسخة — القرار للمستخدم ببيانات كاملة أمامه.
                    keep(rec, kept, errors, CoreOpCode.DEST_EXISTS_INCOMPLETE)
                } else {
                    AtomicIO.moveNoReplace(backup, original)
                    store.remove(rec.opId)
                    restored += rec.opId
                }
            }
        }
    }

    private fun keep(
        rec: ReplaceBackupStore.Record,
        kept: MutableList<String>,
        errors: MutableList<String>,
        why: String
    ) {
        kept += rec.opId
        errors += CoreOpCode.BACKUP_PRESERVED
    }

    /** النسخ الاحتياطية اليتيمة: ملفات `.fm-replace-backup-*` بلا أمر مسجّل (من نسخ أقدم أو سجل تالف). */
    fun orphanReplaceBackups(
        parents: Set<Path>,
        knownBackups: Set<Path>,
        nowMillis: Long = System.currentTimeMillis(),
        minAgeMs: Long = 0L
    ): List<Path> {
        val orphans = ArrayList<Path>()
        for (parent in parents) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) continue
            Files.newDirectoryStream(parent, "$REPLACE_BACKUP_PREFIX*").use { stream ->
                for (p in stream) {
                    if (p in knownBackups) continue
                    if (minAgeMs > 0 && runCatching {
                            Files.getLastModifiedTime(p).toMillis() > nowMillis - minAgeMs
                        }.getOrDefault(false)) continue
                    orphans.add(p)
                }
            }
        }
        return orphans
    }

    /** بادئة النسخ الاحتياطية للاستبدال — بيانات مستخدم لا ملف مؤقت. */
    const val REPLACE_BACKUP_PREFIX = ".fm-replace-backup-"
}
