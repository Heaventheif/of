package org.openfilemanager.app

import java.nio.file.Files
import java.nio.file.Paths
import org.openfilemanager.core.CoreOpCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات تتحقق فعلياً أن محددات التنسيق في `strings.xml` تتطابق مع `argTypes` في `CoreMessageResolver`. */
class CoreMessageFormatVerificationTest {

    /** يقرأ نص مورد من strings.xml (values/ للعربية، values-en/ للإنجليزية). */
    private fun readStringResource(resName: String, locale: String = ""): String? {
        val base = "src/main/res"
        val dir = if (locale.isEmpty()) "$base/values" else "$base/values-$locale"
        val path = Paths.get(dir, "strings.xml")
        if (!Files.exists(path)) return null
        val content = String(Files.readAllBytes(path), Charsets.UTF_8)
        // <string name="resName">value</string>
        val regex = Regex("<string\\s+name=\"$resName\">([^<]*)</string>")
        return regex.find(content)?.groupValues?.get(1)
    }

    /** كل رمز في argTypes يجب أن يكون له مورد سلسلة في strings.xml. */
    @Test
    fun allArgTypesCodesHaveStringResources() {
        val codesWithArgs = listOf(
            CoreOpCode.OP_SUMMARY,
            CoreOpCode.EXTRACTED,
            CoreOpCode.MERGE_SUB_FAILED,
            CoreOpCode.RECOVERY_SUMMARY,
            CoreOpCode.UNDO_SUMMARY,
            CoreOpCode.PART_READ_FAILED,
            CoreOpCode.SPLIT_CORRUPT,
            CoreOpCode.DEST_INSIDE_SOURCE,
            CoreOpCode.NO_OPERATION,
            CoreOpCode.OP_EMPTY,
            CoreOpCode.OP_ALREADY_FINISHED,
            CoreOpCode.BACKUP_PRESERVED,
            CoreOpCode.BOTH_SOURCE_AND_BACKUP_MISSING,
            CoreOpCode.DESTINATION_EXISTS,
        )
        for (code in codesWithArgs) {
            val resId = CoreMessageResolver.stringRes(code)
            assertTrue("Code $code has no stringRes", resId != null)
        }
    }

    /**
     * لكل رمز ذي معاملات: تحقق أن عدد وأنواع المحددات في نص المورد (ar + en)
     * تتطابق مع argTypes. هذا هو الاختبار الفعلي لمنع IllegalFormatException.
     */
    @Test
    fun formatSpecifiersMatchArgTypes() {
        // خريطة: code -> اسم المورد السلسلي
        // تم تنظيف القائمة لتنطبق على argTypes الفعلية فقط.
        val codeToResName = mapOf(
            CoreOpCode.OP_SUMMARY to "core_op_summary",
            CoreOpCode.EXTRACTED to "core_extracted",
            CoreOpCode.MERGE_SUB_FAILED to "core_merge_sub_failed",
            CoreOpCode.RECOVERY_SUMMARY to "core_recovery_summary",
            CoreOpCode.UNDO_SUMMARY to "core_undo_summary",
            CoreOpCode.PART_READ_FAILED to "core_part_read_failed",
            CoreOpCode.SPLIT_CORRUPT to "core_split_corrupt",
            CoreOpCode.DEST_INSIDE_SOURCE to "core_dest_inside_source",
            CoreOpCode.NO_OPERATION to "core_no_operation",
            CoreOpCode.OP_EMPTY to "core_op_empty",
        )
        for ((code, resName) in codeToResName) {
            val arFormat = readStringResource(resName, "")
                ?: error("Arabic string resource '$resName' not found for code $code")
            val enFormat = readStringResource(resName, "en")
                ?: error("English string resource '$resName' not found for code $code")
            assertTrue(
                "Arabic format mismatch for $code (resource $resName): \"$arFormat\" vs argTypes",
                CoreMessageResolver.argsMatchFormat(code, arFormat)
            )
            assertTrue(
                "English format mismatch for $code (resource $resName): \"$enFormat\" vs argTypes",
                CoreMessageResolver.argsMatchFormat(code, enFormat)
            )
        }
    }

    /**
     * اختبار مباشر: convertArgs يُرجع Int للأرقام و String للنصوص.
     * هذا يتحقق أن المعاملات ستكون بالأنواع الصحيحة قبل getString.
     */
    @Test
    fun convertArgsReturnsCorrectTypes() {
        // OP_SUMMARY: "sddd" → [String, Int, Int, Int]
        val args = CoreMessageResolver.convertArgs(
            CoreOpCode.OP_SUMMARY,
            listOf(CoreOpCode.KIND_COPY, "5", "2", "1")
        )
        assertTrue("convertArgs returned null", args != null)
        assertEquals(4, args!!.size)
        assertTrue("arg[0] should be String, got ${args[0].javaClass.simpleName}", args[0] is String)
        assertTrue("arg[1] should be Number, got ${args[1].javaClass.simpleName}", args[1] is Number)
        assertTrue("arg[2] should be Number, got ${args[2].javaClass.simpleName}", args[2] is Number)
        assertTrue("arg[3] should be Number, got ${args[3].javaClass.simpleName}", args[3] is Number)
        assertEquals(5, (args[1] as Number).toInt())
        assertEquals(2, (args[2] as Number).toInt())
        assertEquals(1, (args[3] as Number).toInt())
    }

    @Test
    fun convertArgsHandlesNumericCodes() {
        // MERGE_SUB_FAILED: "d" → [Int]
        val args = CoreMessageResolver.convertArgs(CoreOpCode.MERGE_SUB_FAILED, listOf("3"))
        assertTrue(args != null)
        assertEquals(1, args!!.size)
        assertTrue("Should be Number, got ${args[0].javaClass.simpleName}", args[0] is Number)
        assertEquals(3, (args[0] as Number).toInt())
    }

    @Test
    fun convertArgsHandlesStringCodes() {
        // PART_READ_FAILED: "s" → [String]
        val args = CoreMessageResolver.convertArgs(CoreOpCode.PART_READ_FAILED, listOf("part1.zip"))
        assertTrue(args != null)
        assertEquals(1, args!!.size)
        assertTrue("Should be String, got ${args[0].javaClass.simpleName}", args[0] is String)
        assertEquals("part1.zip", args[0])
    }

    @Test
    fun convertArgsReturnsNullForUnknownCode() {
        assertEquals(null, CoreMessageResolver.convertArgs("UNKNOWN_CODE", listOf("a", "b")))
    }

    @Test
    fun convertArgsHandlesNonNumericValueForIntArg() {
        // معامل رقمي لكن القيمة ليست رقماً → 0 (لا رمي استثناء)
        val args = CoreMessageResolver.convertArgs(CoreOpCode.MERGE_SUB_FAILED, listOf("not-a-number"))
        assertTrue(args != null)
        assertEquals(0, (args!![0] as Number).toInt())
    }

    /**
     * اختبار شامل: بناء رسالة OP_SUMMARY، تقسيمها، تحويل المعاملات،
     * ثم التحقق أن الأنواع تطابق ما يتوقعه strings.xml.
     */
    @Test
    fun fullOpSummaryPipelineProducesCorrectTypes() {
        // 1. بناء الرسالة الخام
        val raw = CoreOpCode.message(CoreOpCode.OP_SUMMARY, CoreOpCode.KIND_COPY, 5, 2, 1)
        // 2. تقسيمها
        val parts = raw.split(CoreOpCode.ARG_SEP)
        assertEquals(5, parts.size)  // code + 4 args
        assertEquals(CoreOpCode.OP_SUMMARY, parts[0])
        // 3. تحويل المعاملات
        val typedArgs = CoreMessageResolver.convertArgs(CoreOpCode.OP_SUMMARY, parts.drop(1))
        assertTrue("convertArgs returned null", typedArgs != null)
        // 4. التحقق أن عدد المعاملات يطابق المورد
        val format = readStringResource("core_op_summary", "")
        assertTrue("Format string not found", format != null)
        assertTrue(
            "OP_SUMMARY args don't match format \"$format\"",
            CoreMessageResolver.argsMatchFormat(CoreOpCode.OP_SUMMARY, format!!)
        )
    }
}
