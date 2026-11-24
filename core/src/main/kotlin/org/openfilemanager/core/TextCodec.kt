package org.openfilemanager.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * فك/ترميز نصوص المحرر (7.1/7.2): منطق صرف قابل للاختبار على JVM.
 *
 * الفرق الجوهري بين مسارين:
 * - المعاينة (قراءة فقط لملف كبير): فك متسامح (REPLACE) — قطع المعاينة عند حد
 *   البايتات قد يقطع محرفاً متعدد البايتات في منتصفه، والفك الصارم كان يعرض
 *   حوار «ليس UTF-8» زوراً على نص عربي سليم.
 * - التحميل الكامل (تحرير): فك صارم (REPORT) — ملف تالف فعلاً يُعرض على
 *   المستخدم بقراره (فتح بترميز آخر) لا بديل صامت.
 */
object TextCodec {

    /** فك صارم: يرمي CharacterCodingException عند أي بايت غير صالح. */
    @Throws(CharacterCodingException::class)
    fun decodeStrict(bytes: ByteArray, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()

    /** فك متسامح للمعاينة: المحارف غير الصالحة تُستبدل لا تُرفض. */
    fun decodeLenient(bytes: ByteArray, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes)).toString()

    /** ترميز صارم: يرمي CharacterCodingException إن لم يتمثل المحرف في الترميز. */
    @Throws(CharacterCodingException::class)
    fun encodeStrict(text: String, charset: Charset): ByteArray {
        val buffer = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    /**
     * يقرأ BOM إن وُجد: UTF-8 (EF BB BF) أو UTF-16BE (FE FF) أو UTF-16LE (FF FE).
     * يعيد مصفوفة فارغة إن لم يوجد.
     */
    fun bomOf(b: ByteArray): ByteArray = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> b.copyOf(3)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> b.copyOf(2)
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> b.copyOf(2)
        else -> ByteArray(0)
    }

    /** الترميز المبدئي من BOM؛ UTF-8 إن لم يوجد. */
    fun charsetFromBom(bom: ByteArray): Charset = when {
        bom.size == 3 -> Charsets.UTF_8
        bom.size == 2 && bom[0] == 0xFE.toByte() -> Charsets.UTF_16BE
        bom.size == 2 -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
}
