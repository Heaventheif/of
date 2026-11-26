package org.openfilemanager.app

import androidx.annotation.StringRes
import org.openfilemanager.core.CoreOpCode

/** يحوّل رموز `CoreOpCode` (النصوص الثابتة التي يُرجعها `:core`) إلى معرّفات موارد سلاسل مترجمة في `strings.xml`. */
object CoreMessageResolver {

    /**
     * أنواع معاملات كل رمز: `"d"` = رقم (Int)، `"s"` = نص. الترتيب يطابق ترتيب
     * المعاملات في `CoreOpCode.message(code, args...)`. الرموز غير المذكورة هنا
     * إما بلا معاملات أو معاملاتها كلها نصوص.
     */
    private val argTypes: Map<String, String> = mapOf(
        // format: code -> "dsd" means arg1=Int, arg2=String, arg3=Int تم تنظيف القائمة: الرموز التي أُسقطت معاملاتها أثناء COMPLEX_MAP (BACKUP_PRESERVED,…
        CoreOpCode.OP_SUMMARY to "sddd",         // kindLabel:String, ok:Int, failed:Int, skipped:Int
        CoreOpCode.EXTRACTED to "d",              // count:Int
        CoreOpCode.MERGE_SUB_FAILED to "d",       // count:Int
        CoreOpCode.RECOVERY_SUMMARY to "dd",      // finished:Int, restored:Int
        CoreOpCode.UNDO_SUMMARY to "d",           // restored:Int
        CoreOpCode.PART_READ_FAILED to "s",       // partName:String
        CoreOpCode.SPLIT_CORRUPT to "s",          // split:String
        CoreOpCode.DEST_INSIDE_SOURCE to "s",     // source:String
        CoreOpCode.NO_OPERATION to "s",           // opId:String
        CoreOpCode.OP_EMPTY to "s",               // opId:String
        // DESTINATION_EXISTS يُستعمل مع معامل أحياناً (ZipCreator) وبلا معامل
        // أحياناً (ReplaceBackup). المورد بلا %1$s، فالمعامل يُتجاهل بأمان.
        // لذا لا يظهر هنا — convertArgs يُرجع null، و resolve يستعمل args خام.
    )

    /** يعيد معرف المورد السلسلي للرمز، أو null إن لم يُعرف. */
    @StringRes
    fun stringRes(code: String): Int? = when (code) {
        CoreOpCode.CANCELLED -> R.string.core_op_cancelled
        CoreOpCode.FAILED -> R.string.core_op_failed
        CoreOpCode.ARCHIVE_CREATED -> R.string.core_archive_created
        CoreOpCode.EXTRACTED -> R.string.core_extracted
        CoreOpCode.DELETE_FAILED -> R.string.core_delete_failed
        CoreOpCode.TRANSFER_FAILED -> R.string.core_transfer_failed
        CoreOpCode.COPY_FAILED -> R.string.core_copy_failed
        CoreOpCode.COMPRESS_FAILED -> R.string.core_compress_failed
        CoreOpCode.EXTRACT_FAILED -> R.string.core_extract_failed
        CoreOpCode.RESUME_FAILED -> R.string.core_resume_failed
        CoreOpCode.PART_READ_FAILED -> R.string.core_part_read_failed
        CoreOpCode.MERGE_SUB_FAILED -> R.string.core_merge_sub_failed
        CoreOpCode.DESTINATION_EXISTS -> R.string.core_destination_exists
        CoreOpCode.DESTINATION_IS_FILE -> R.string.core_destination_is_file
        CoreOpCode.DESTINATION_EXISTS_RESTORED -> R.string.core_destination_exists_restored
        CoreOpCode.SOURCE_MISSING -> R.string.core_source_missing
        CoreOpCode.NO_OPERATION -> R.string.core_no_operation
        CoreOpCode.NOT_RENAME_OP -> R.string.core_not_rename_op
        CoreOpCode.OP_INTERRUPTED -> R.string.core_op_interrupted
        CoreOpCode.OP_ALREADY_FINISHED -> R.string.core_op_already_finished
        CoreOpCode.OP_EMPTY -> R.string.core_op_empty
        CoreOpCode.RENAME_RECOVERED_BY_ENGINE -> R.string.core_rename_recovered_by_engine
        CoreOpCode.NO_ITEMS -> R.string.core_no_items
        CoreOpCode.NO_VALID_FILES -> R.string.core_no_valid_files
        CoreOpCode.SPLIT_CORRUPT -> R.string.core_split_corrupt
        CoreOpCode.SPLIT_TOO_LARGE -> R.string.core_split_too_large
        CoreOpCode.SAME_PATH -> R.string.core_same_path
        CoreOpCode.SAME_FILE -> R.string.core_same_file
        CoreOpCode.SKIP_GROUP -> R.string.core_skip_group
        CoreOpCode.NO_EXTRACT_LOG -> R.string.core_no_extract_log
        CoreOpCode.DEST_EXISTS_INCOMPLETE -> R.string.core_dest_exists_incomplete
        CoreOpCode.FINISHED_DEST_MISSING -> R.string.core_finished_dest_missing
        CoreOpCode.BOTH_SOURCE_AND_BACKUP_MISSING -> R.string.core_both_missing
        CoreOpCode.BACKUP_PRESERVED -> R.string.core_backup_preserved
        CoreOpCode.RECOVERY_SUMMARY -> R.string.core_recovery_summary
        CoreOpCode.UNDO_SUMMARY -> R.string.core_undo_summary
        CoreOpCode.SYMLINK -> R.string.core_symlink
        CoreOpCode.DEST_INSIDE_SOURCE -> R.string.core_dest_inside_source
        CoreOpCode.KIND_COPY -> R.string.core_kind_copy
        CoreOpCode.KIND_MOVE -> R.string.core_kind_move
        CoreOpCode.KIND_DELETE -> R.string.core_kind_delete
        CoreOpCode.KIND_OPERATION -> R.string.core_kind_operation
        CoreOpCode.OP_SUMMARY -> R.string.core_op_summary
        else -> null
    }

    /** يحوّل معاملات السلاسل النصية (من `CoreOpCode.message`) إلى أنواعها الصحيحة (Int للأرقام، String للنصوص) استعداداً لـ `getString`. */
    fun convertArgs(code: String, args: List<String>): Array<Any>? {
        val types = argTypes[code] ?: return null
        return args.mapIndexedNotNull { idx, a ->
            if (idx >= types.length) a
            else when (types[idx]) {
                'd' -> (a.toIntOrNull() ?: a.toLongOrNull() ?: 0) as Number
                else -> a
            }
        }.toTypedArray()
    }

    /** يتحقق أن عدد وأنواع معاملات [argTypes] لرمز ما تتطابق مع محددات التنسيق (`%d`, `%s`) في نص المورد. */
    fun argsMatchFormat(code: String, formatString: String): Boolean {
        val types = argTypes[code]
        // استخراج محددات التنسيق: %1$d, %2$s, %03$d, %#x, إلخ.
        val specs = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[dfscoxXbBeEgGaAhHnS%]")
            .findAll(formatString)
            .filter { it.value != "%" && it.value != "%%" }
            .map { m ->
                val v = m.value
                // نوع المحدد آخر حرف (d/f/s/c/...)
                v.last().lowercaseChar()
            }
            .toList()
        // (تصحيح v5) رمز بلا argTypes يتوقع صيغة بلا محددات إطلاقاً: لو كانت
        // الصيغة ذات placeholders فسيُستدعى getString بلا معاملات ويرمي —
        // يُعلَّم ذلك عدم تطابق بدل قبوله صامتاً.
        if (types == null) return specs.isEmpty()
        if (specs.isEmpty()) return types.isEmpty()
        if (specs.size != types.length) return false
        return specs.mapIndexed { idx, spec ->
            when (types[idx]) {
                'd' -> spec == 'd' || spec == 'f' || spec == 'x' || spec == 'o' || spec == 'e' || spec == 'g'
                else -> spec == 's' || spec == 'c'
            }
        }.all { it }
    }

    /** ينسّق رسالة قد تكون `"CODE"` أو `"CODE\u001Farg1\u001Farg2"` (الفاصل آمن: ASCII Unit Separator، لا يتكسر مع مسارات Windows). */
    fun resolve(context: android.content.Context, raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sep = CoreOpCode.ARG_SEP
        val sepIdx = raw.indexOf(sep)
        val code = if (sepIdx > 0) raw.substring(0, sepIdx) else raw
        val args = if (sepIdx > 0) raw.substring(sepIdx + 1).split(sep) else emptyList()
        val resId = stringRes(code) ?: return raw
        return try {
            if (code == CoreOpCode.OP_SUMMARY && args.size >= 4) {
                // OP_SUMMARY: args = [kindLabel, ok, failed, skipped]
                // kindLabel يُترجم أولاً، البقية أرقام.
                val kindRes = stringRes(args[0])
                val kindText = if (kindRes != null) context.getString(kindRes) else args[0]
                context.getString(resId, kindText, args[1].toIntOrNull() ?: 0, args[2].toIntOrNull() ?: 0, args[3].toIntOrNull() ?: 0)
            } else if (args.isEmpty()) {
                context.getString(resId)
            } else {
                // استخدم convertArgs الموحّدة (قابلة للاختبار بدون Context).
                val typedArgs = convertArgs(code, args) ?: args.toTypedArray()
                context.getString(resId, *typedArgs)
            }
        } catch (_: Throwable) {
            raw
        }
    }
}
