package org.openfilemanager.app.history

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

enum class OperationOutcomeState { COMPLETED, PARTIAL, FAILED, CANCELLED }

/** مشتق خالص من العدّادات — يُختبر على JVM ويوثّق التعيين في ملاحظة التصميم. */
fun outcomeStateOf(ok: Int, failed: Int, skipped: Int): OperationOutcomeState = when {
    ok > 0 && failed == 0 && skipped == 0 -> OperationOutcomeState.COMPLETED
    ok > 0 -> OperationOutcomeState.PARTIAL
    failed > 0 -> OperationOutcomeState.FAILED
    else -> OperationOutcomeState.CANCELLED
}

/**
 * مدخل واحد في سجل تاريخ العمليات: النوع (اسم OpKind أو null لعملية استئناف
 * مجهولة النوع)، ملخّص العدّادات ok/failed/skipped، حالة الخاتمة، وطابع الانتهاء.
 */
data class OperationHistoryEntry(
    val kind: String?,
    val ok: Int,
    val failed: Int,
    val skipped: Int,
    val state: OperationOutcomeState,
    val finishedAt: Long
)

class OperationHistoryStore(private val root: File) {

    private val dir: File get() = File(root, DIR_NAME)
    private val file: File get() = File(dir, FILE_NAME)

    /** يضيف خاتمة عملية إلى نهاية السجل ويقتصّ الأقدم فوق الحد. آمن خيطياً. */
    @Synchronized
    fun record(kind: String?, ok: Int, failed: Int, skipped: Int) {
        val entry = OperationHistoryEntry(
            kind = kind?.takeIf { it.isNotBlank() },
            ok = ok.coerceIn(0, MAX_COUNT),
            failed = failed.coerceIn(0, MAX_COUNT),
            skipped = skipped.coerceIn(0, MAX_COUNT),
            state = outcomeStateOf(ok, failed, skipped),
            finishedAt = System.currentTimeMillis()
        )
        write(entries() + entry)
    }

    /**
     * قراءة السجل كله (الأقدم أولاً حسب ترتيب الإضافة): قائمة جديدة كل مرة
     * (لقطة للعرض فقط — تعديلها لا يفسد المخزن)، والأسطر التالفة تُتخطى.
     */
    @Synchronized
    fun entries(): List<OperationHistoryEntry> {
        val f = file
        if (!f.isFile) return emptyList()
        val out = ArrayList<OperationHistoryEntry>()
        runCatching {
            BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    decode(line)?.let { out += it }
                    line = r.readLine()
                }
            }
        }
        // حماية إضافية: لو كُتب الملف ذات يوم فوق الحد (إصدار قديم) يُقتصّ عند القراءة.
        return if (out.size > MAX_ENTRIES) out.takeLast(MAX_ENTRIES) else out
    }

    /** مسح السجل كله (زر المستخدم): حذف الملف — أفضل جهد، الفراغ بعد المسح مضمون. */
    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    /** إعادة كتابة كاملة: اقتصام الحد ثم مؤقت + مزامنة + إعادة تسمية ذرّية. */
    private fun write(all: List<OperationHistoryEntry>) {
        val kept = if (all.size > MAX_ENTRIES) all.takeLast(MAX_ENTRIES) else all
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("cannot create op-history directory")
            val temp = File(dir, ".tmp-$FILE_NAME-${System.nanoTime()}")
            try {
                FileOutputStream(temp).use { raw ->
                    BufferedWriter(OutputStreamWriter(raw, StandardCharsets.UTF_8)).use { w ->
                        for (e in kept) {
                            w.write(encode(e))
                            w.newLine()
                        }
                        w.flush()
                        raw.fd.sync()
                    }
                }
                try {
                    java.nio.file.Files.move(
                        temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } catch (t: Throwable) {
                runCatching { temp.delete() }
                throw t
            }
        } catch (t: Throwable) {
            // التسجيل أفضل جهد: فشل القرص لا يُسقط نشر النتيجة القائم في OpsCenter.
            java.util.logging.Logger.getLogger(OperationHistoryStore::class.java.name)
                .warning("operation history write failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        /** حدّ الاحتفاظ: يُرمى الأقدم فوقه (ثابت مُسمّى للاختبار والمراجعة). */
        const val MAX_ENTRIES = 100

        /** سقف عدّاد واحد في التخزين (دفاع ضد قيم مفتوحة من إصدار مستقبلي). */
        const val MAX_COUNT = 999_999_999

        private const val DIR_NAME = "op-history"
        private const val FILE_NAME = "history.log"

        /** سطر مخزَّن: kind,ok,failed,skipped,state,finishedAt — بلا نص حر لا تهريب. */
        private val LINE = Regex("([A-Z]{0,16}),(\\d{1,9}),(\\d{1,9}),(\\d{1,9}),([A-Z]{1,16}),(\\d{1,15})")

        private fun encode(e: OperationHistoryEntry): String =
            "${e.kind ?: ""},${e.ok},${e.failed},${e.skipped},${e.state.name},${e.finishedAt}"

        /** يفك سطراً صالحاً فقط؛ أي انحراف (سطر تالف/مبتور) يعيد null فيُتخطى. */
        private fun decode(line: String): OperationHistoryEntry? {
            val m = LINE.matchEntire(line.trim()) ?: return null
            val state = runCatching { OperationOutcomeState.valueOf(m.groupValues[5]) }.getOrNull() ?: return null
            return OperationHistoryEntry(
                kind = m.groupValues[1].ifEmpty { null },
                ok = m.groupValues[2].toInt(),
                failed = m.groupValues[3].toInt(),
                skipped = m.groupValues[4].toInt(),
                state = state,
                finishedAt = m.groupValues[6].toLong()
            )
        }
    }
}
