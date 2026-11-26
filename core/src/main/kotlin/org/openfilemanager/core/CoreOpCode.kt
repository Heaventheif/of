package org.openfilemanager.core

/** رموز ثابتة لرسائل المحرك — كانت 65 نصاً عربياً مضمّناً في كود الإنتاج داخل `:core`، وهذا يخرق استقلالية المحرك: لو أراد أحد دعم لغة أخرى لاحظت أن. */
object CoreOpCode {
    // ---- Operation outcomes (stored in journal kindLabel) ----
    /** العملية أُلغيت بنجاح. */
    const val CANCELLED = "OP_CANCELLED"
    /** العملية فشلت. */
    const val FAILED = "OP_FAILED"
    /** الأرشيف أُنشئ بنجاح (journal kindLabel للضغط). */
    const val ARCHIVE_CREATED = "ARCHIVE_CREATED"
    /** الاستخراج اكتمل (journal kindLabel). */
    const val EXTRACTED = "EXTRACTED"

    // ---- Failure reasons (exception messages) ----
    const val DELETE_FAILED = "DELETE_FAILED"
    const val TRANSFER_FAILED = "TRANSFER_FAILED"
    const val COPY_FAILED = "COPY_FAILED"
    const val COMPRESS_FAILED = "COMPRESS_FAILED"
    const val EXTRACT_FAILED = "EXTRACT_FAILED"
    const val RESUME_FAILED = "RESUME_FAILED"
    const val PART_READ_FAILED = "PART_READ_FAILED"
    /** تعذر نقل N عنصراً أثناء الدمج — يتبعه `:N`. */
    const val MERGE_SUB_FAILED = "MERGE_SUB_FAILED"

    // ---- State / condition messages ----
    const val DESTINATION_EXISTS = "DESTINATION_EXISTS"
    const val DESTINATION_IS_FILE = "DESTINATION_IS_FILE"
    const val DESTINATION_EXISTS_RESTORED = "DESTINATION_EXISTS_RESTORED"
    const val SOURCE_MISSING = "SOURCE_MISSING"
    const val NO_OPERATION = "NO_OPERATION"
    const val NOT_RENAME_OP = "NOT_RENAME_OP"
    const val OP_INTERRUPTED = "OP_INTERRUPTED"
    const val OP_ALREADY_FINISHED = "OP_ALREADY_FINISHED"
    const val OP_EMPTY = "OP_EMPTY"
    const val RENAME_RECOVERED_BY_ENGINE = "RENAME_RECOVERED_BY_ENGINE"
    const val NO_ITEMS = "NO_ITEMS"
    const val NO_VALID_FILES = "NO_VALID_FILES"
    const val SPLIT_CORRUPT = "SPLIT_CORRUPT"
    const val SPLIT_TOO_LARGE = "SPLIT_TOO_LARGE"
    const val SAME_PATH = "SAME_PATH"
    const val SAME_FILE = "SAME_FILE"
    const val SKIP_GROUP = "SKIP_GROUP"
    const val NO_EXTRACT_LOG = "NO_EXTRACT_LOG"
    const val DEST_EXISTS_INCOMPLETE = "DEST_EXISTS_INCOMPLETE"
    const val FINISHED_DEST_MISSING = "FINISHED_DEST_MISSING"
    const val BOTH_SOURCE_AND_BACKUP_MISSING = "BOTH_MISSING"
    const val BACKUP_PRESERVED = "BACKUP_PRESERVED"
    /** استرداد التسمية: أكمل N، أعاد M — يتبعه `:N:M`. */
    const val RECOVERY_SUMMARY = "RECOVERY_SUMMARY"
    /** إلغاء التسمية: أعاد N — يتبعه `:N`. */
    const val UNDO_SUMMARY = "UNDO_SUMMARY"
    const val SYMLINK = "SYMLINK"
    const val DEST_INSIDE_SOURCE = "DEST_INSIDE_SOURCE"

    // ---- Operation kind labels (OpRecoveryCoordinator summary) ----
    const val KIND_COPY = "COPY"
    const val KIND_MOVE = "MOVE"
    const val KIND_DELETE = "DELETE"
    const val KIND_OPERATION = "OPERATION"
    /** ملخص نتيجة عملية: `OP_SUMMARY\u001FkindLabel\u001Fok\u001Ffailed\u001Fskipped` — يُترجم عبر `core_op_summary`. */
    const val OP_SUMMARY = "OP_SUMMARY"

    /** فاصل آمن بين الرمز ومعاملاته. */
    const val ARG_SEP = '\u001F'

    /**
     * يبني رسالة رمزية مع معامل: `"CODE\u001Farg1\u001Farg2"`. المستدعي في طبقة
     * التطبيق يفصل الرمز عن المعاملات عبر [ARG_SEP] وينسّقه محلياً.
     */
    fun message(code: String, vararg args: Any?): String =
        if (args.isEmpty()) code else code + ARG_SEP + args.joinToString(ARG_SEP.toString())
}
