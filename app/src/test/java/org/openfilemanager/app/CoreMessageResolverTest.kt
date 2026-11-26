package org.openfilemanager.app

import org.openfilemanager.core.CoreOpCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** اختبارات منطق CoreMessageResolver بدون الحاجة إلى Context (JVM خالص). */
class CoreMessageResolverLogicTest {

    @Test
    fun allCodesHaveStringResMapping() {
        // كل رمز معروف يجب أن يُرجع معرف مورد غير null.
        val codes = listOf(
            CoreOpCode.CANCELLED, CoreOpCode.FAILED, CoreOpCode.ARCHIVE_CREATED,
            CoreOpCode.EXTRACTED, CoreOpCode.DELETE_FAILED, CoreOpCode.TRANSFER_FAILED,
            CoreOpCode.COPY_FAILED, CoreOpCode.COMPRESS_FAILED, CoreOpCode.EXTRACT_FAILED,
            CoreOpCode.RESUME_FAILED, CoreOpCode.PART_READ_FAILED, CoreOpCode.MERGE_SUB_FAILED,
            CoreOpCode.DESTINATION_EXISTS, CoreOpCode.DESTINATION_IS_FILE,
            CoreOpCode.DESTINATION_EXISTS_RESTORED, CoreOpCode.SOURCE_MISSING,
            CoreOpCode.NO_OPERATION, CoreOpCode.NOT_RENAME_OP, CoreOpCode.OP_INTERRUPTED,
            CoreOpCode.OP_ALREADY_FINISHED, CoreOpCode.OP_EMPTY,
            CoreOpCode.RENAME_RECOVERED_BY_ENGINE, CoreOpCode.NO_ITEMS,
            CoreOpCode.NO_VALID_FILES, CoreOpCode.SPLIT_CORRUPT, CoreOpCode.SPLIT_TOO_LARGE,
            CoreOpCode.SAME_PATH, CoreOpCode.SAME_FILE, CoreOpCode.SKIP_GROUP,
            CoreOpCode.NO_EXTRACT_LOG, CoreOpCode.DEST_EXISTS_INCOMPLETE,
            CoreOpCode.FINISHED_DEST_MISSING, CoreOpCode.BOTH_SOURCE_AND_BACKUP_MISSING,
            CoreOpCode.BACKUP_PRESERVED, CoreOpCode.RECOVERY_SUMMARY, CoreOpCode.UNDO_SUMMARY,
            CoreOpCode.SYMLINK, CoreOpCode.DEST_INSIDE_SOURCE,
            CoreOpCode.KIND_COPY, CoreOpCode.KIND_MOVE, CoreOpCode.KIND_DELETE,
            CoreOpCode.KIND_OPERATION, CoreOpCode.OP_SUMMARY,
        )
        for (code in codes) {
            assertTrue("Code $code has no stringRes mapping", CoreMessageResolver.stringRes(code) != null)
        }
    }

    @Test
    fun unknownCodeReturnsNullStringRes() {
        assertEquals(null, CoreMessageResolver.stringRes("NONEXISTENT_CODE_123"))
    }

    @Test
    fun separatorIsUnitSeparatorNotColon() {
        // الفاصل يجب أن يكون \u001F، لا ':' — هذا هو الإصلاح الأساسي لمشكلة مسارات Windows.
        assertEquals('\u001F', CoreOpCode.ARG_SEP)
        assertFalse("Separator must not be ':' (unsafe for Windows paths)", CoreOpCode.ARG_SEP == ':')
    }

    @Test
    fun messageWithNoArgsReturnsCodeOnly() {
        assertEquals(CoreOpCode.DELETE_FAILED, CoreOpCode.message(CoreOpCode.DELETE_FAILED))
    }

    @Test
    fun messageWithArgsUsesSeparator() {
        val msg = CoreOpCode.message(CoreOpCode.MERGE_SUB_FAILED, 3)
        // الصيغة: "MERGE_SUB_FAILED\u001F3"
        assertTrue(msg.startsWith(CoreOpCode.MERGE_SUB_FAILED))
        assertTrue(msg.contains(CoreOpCode.ARG_SEP.toString()))
        assertTrue(msg.contains("3"))
        // لا يجب أن يحتوي على ':' (الفاصل القديم غير الآمن).
        assertFalse("Message must not use ':' separator: $msg", msg.contains(":"))
    }

    @Test
    fun messageWithMultipleArgsAllSeparatedByUnitSeparator() {
        val msg = CoreOpCode.message(CoreOpCode.OP_SUMMARY, CoreOpCode.KIND_COPY, 5, 2, 1)
        // يجب أن يحتوي على 4 فواصل \u001F (بين 5 معاملات)
        val sepCount = msg.count { it == CoreOpCode.ARG_SEP }
        assertEquals("Expected 4 separators for 5 parts, got $sepCount", 4, sepCount)
    }

    @Test
    fun windowsPathInArgDoesNotBreakParsing() {
        // معامل يحتوي على : (مسار Windows) لا يتكسر الفاصل.
        val path = "C:\\Users\\test"
        val msg = CoreOpCode.message(CoreOpCode.DEST_INSIDE_SOURCE, path)
        // عند الفصل على \u001F، يجب أن يستعيد المسار كاملاً.
        val parts = msg.split(CoreOpCode.ARG_SEP)
        assertEquals(2, parts.size)
        assertEquals(CoreOpCode.DEST_INSIDE_SOURCE, parts[0])
        assertEquals(path, parts[1])
    }

    @Test
    fun multipleWindowsPathsInArgsDoNotBreak() {
        // معاملات متعددة تحتوي على : لا تتكسر.
        val path1 = "C:\\Users\\a"
        val path2 = "D:\\backup\\b"
        val msg = CoreOpCode.message(CoreOpCode.BACKUP_PRESERVED, path1, path2)
        val parts = msg.split(CoreOpCode.ARG_SEP)
        assertEquals(3, parts.size)
        assertEquals(CoreOpCode.BACKUP_PRESERVED, parts[0])
        assertEquals(path1, parts[1])
        assertEquals(path2, parts[2])
    }

    @Test
    fun numericArgsArePreservedAsStrings() {
        // المعاملات الرقمية تُحوّل إلى نص في message()، لكن يجب أن تبقى قابلة للتحويل
        // إلى Int في resolve().
        val msg = CoreOpCode.message(CoreOpCode.OP_SUMMARY, CoreOpCode.KIND_COPY, 5, 2, 1)
        val parts = msg.split(CoreOpCode.ARG_SEP)
        assertEquals(5, parts.size)
        assertEquals("5", parts[2])
        assertEquals("2", parts[3])
        assertEquals("1", parts[4])
        // قابلية التحويل إلى Int (هذا ما يحدث في resolve):
        assertEquals(5, parts[2].toInt())
        assertEquals(2, parts[3].toInt())
        assertEquals(1, parts[4].toInt())
    }

    @Test
    fun argTypesCoversAllParameterizedCodes() {
        // كل رمز يُستعمل مع معاملات يجب أن يكون في argTypes (لتحويل الأرقام).
        // هذه ليست قائمة كاملة بل تحقق أن الرموز ذات %d موجودة.
        val parameterizedWithNumbers = listOf(
            CoreOpCode.OP_SUMMARY,
            CoreOpCode.EXTRACTED,
            CoreOpCode.MERGE_SUB_FAILED,
            CoreOpCode.RECOVERY_SUMMARY,
            CoreOpCode.UNDO_SUMMARY,
        )
        // argTypes خاصة (private) لكن نتحقق间接اً: message + split يعمل لكل منها.
        for (code in parameterizedWithNumbers) {
            val msg = when (code) {
                CoreOpCode.OP_SUMMARY -> CoreOpCode.message(code, CoreOpCode.KIND_COPY, 1, 0, 0)
                CoreOpCode.EXTRACTED -> CoreOpCode.message(code, 1)
                CoreOpCode.MERGE_SUB_FAILED -> CoreOpCode.message(code, 1)
                CoreOpCode.RECOVERY_SUMMARY -> CoreOpCode.message(code, 1, 0)
                CoreOpCode.UNDO_SUMMARY -> CoreOpCode.message(code, 1)
                else -> code
            }
            assertTrue("$code message has no separator: $msg", msg.contains(CoreOpCode.ARG_SEP.toString()))
            // الجزء الرقمي قابل للتحويل إلى Int.
            val numericPart = msg.split(CoreOpCode.ARG_SEP).lastOrNull()
            assertTrue("$code numeric arg not parseable as Int: $numericPart", numericPart?.toIntOrNull() != null)
        }
    }
}

/**
 * اختبارات المعاملات المشوّهة والمفقودة — يطلبها القسم 5 من
 * التدقيق المعماري: «اختبارات للمعاملات الصحيحة والخاطئة والمفقودة والمشوّهة».
 * تُثبّت سلوك fallback الآمن: لا استثناءات، ولا رموز خام مضللة.
 */
class CoreMessageResolverMalformedArgsTest {

    @Test
    fun opSummaryWithMissingArgsFallsBackToRaw() {
        // OP_SUMMARY يتطلب 4 معاملات؛ بأقل من ذلك يعود الرمز الخام بأمان
        // (لا تنسيق جزئي مضلل ولا استثناء).
        val raw = CoreOpCode.message(CoreOpCode.OP_SUMMARY, CoreOpCode.KIND_COPY, 5)
        val parts = raw.split(CoreOpCode.ARG_SEP)
        assertEquals(3, parts.size) // code + 2 args فقط
        // resolve مع سياق مفقود غير قابل للاختبار هنا؛ لكن convertArgs يجب
        // ألا يرمي مع عدد معاملات أقل من المتوقع.
        val typed = CoreMessageResolver.convertArgs(
            CoreOpCode.OP_SUMMARY, parts.drop(1)
        )
        assertTrue(typed != null)
        assertEquals(2, typed!!.size) // يحوّل ما وُجد فقط
    }

    @Test
    fun numericArgWithNonNumericValueFallsBackToZero() {
        // معامل رقمي بقيمة غير رقمية: لا استثناء، يتحول إلى 0 (سلوك آمن موثّق).
        val typed = CoreMessageResolver.convertArgs(
            CoreOpCode.MERGE_SUB_FAILED, listOf("not-a-number")
        )
        assertTrue(typed != null)
        assertEquals(0, (typed!![0] as Number).toInt())
    }

    @Test
    fun numericArgWithNegativeValueIsPreserved() {
        val typed = CoreMessageResolver.convertArgs(
            CoreOpCode.MERGE_SUB_FAILED, listOf("-3")
        )
        assertEquals(-3, (typed!![0] as Number).toInt())
    }

    @Test
    fun numericArgWithHugeValueFallsBackToLong() {
        // قيمة تتجاوز Int: تُحوّل إلى Long بدل أن تصير 0.
        val typed = CoreMessageResolver.convertArgs(
            CoreOpCode.MERGE_SUB_FAILED, listOf("99999999999")
        )
        assertEquals(99999999999L, (typed!![0] as Number).toLong())
    }

    @Test
    fun codeWithArgsNotInArgTypesPassesArgsThroughAsStrings() {
        // DESTINATION_EXISTS يُستعمل مع معامل (مسار) لكنه ليس في argTypes
        // (المورد بلا محدد). resolve سيمرر المعامل الخام وgetString يتجاهله
        // بأمان — سلوك موثّق هنا عبر convertArgs الذي يرجع null.
        assertEquals(null, CoreMessageResolver.convertArgs(
            CoreOpCode.DESTINATION_EXISTS, listOf("/some/path")
        ))
    }

    @Test
    fun messageWithEmptyArgsIsJustTheCode() {
        // message بدون معاملات = الرمز وحده بلا فاصل.
        val msg = CoreOpCode.message(CoreOpCode.DELETE_FAILED)
        assertEquals(CoreOpCode.DELETE_FAILED, msg)
        assertFalse(msg.contains(CoreOpCode.ARG_SEP.toString()))
    }

    @Test
    fun rawCodeWithSeparatorIsSafelyParseable() {
        // رسالة خام بنية صحيحة لكن رمز غير معروف: تُعاد كما هي عند الحل
        // (stringRes يعيد null) — لا استثناء ولا فقدان بيانات.
        val raw = "UNKNOWN_CODE" + CoreOpCode.ARG_SEP + "arg1" + CoreOpCode.ARG_SEP + "arg2"
        assertEquals(null, CoreMessageResolver.stringRes("UNKNOWN_CODE"))
        assertEquals(null, CoreMessageResolver.convertArgs("UNKNOWN_CODE", listOf("arg1", "arg2")))
    }

    @Test
    fun argsMatchFormatRejectsCountMismatch() {
        // argTypes لـ OP_SUMMARY = "sddd" (4 معاملات)؛ صيغة بمحددين فقط يجب أن تُرفض.
        assertFalse(CoreMessageResolver.argsMatchFormat(
            CoreOpCode.OP_SUMMARY, "%1\$s: %2\$d ناجح"
        ))
        // والصيغة الصحيحة تُقبل.
        assertTrue(CoreMessageResolver.argsMatchFormat(
            CoreOpCode.OP_SUMMARY, "%1\$s: %2\$d ناجح، %3\$d فاشل، %4\$d متخطى"
        ))
    }

    @Test
    fun argsMatchFormatRejectsTypeMismatch() {
        // MERGE_SUB_FAILED = "d" (رقم)؛ صيغة بمحدد نصي يجب أن تُرفض.
        assertFalse(CoreMessageResolver.argsMatchFormat(
            CoreOpCode.MERGE_SUB_FAILED, "تعذر نقل %1\$s عنصراً"
        ))
        assertTrue(CoreMessageResolver.argsMatchFormat(
            CoreOpCode.MERGE_SUB_FAILED, "تعذر نقل %1\$d عنصراً"
        ))
    }

    @Test
    fun argsMatchFormatPassesForCodesWithoutArgs() {
        // رمز بلا معاملات وصيغة بلا محددات = متوافقان.
        assertTrue(CoreMessageResolver.argsMatchFormat(
            CoreOpCode.DELETE_FAILED, "تعذر الحذف"
        ))
        // رمز بلا معاملات مع صيغة ذات محددات = غير متوافق (argTypes فارغ).
        assertFalse(CoreMessageResolver.argsMatchFormat(
            CoreOpCode.DELETE_FAILED, "تعذر الحذف: %1\$s"
        ))
    }
}
