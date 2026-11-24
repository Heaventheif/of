package org.openfilemanager.core

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/** نص محمّل مع الترميز الذي قُرئ به، ليُحفظ بالترميز نفسه فلا يتلف الأصل. */
class DecodedText(val text: String, val charset: Charset, val bom: ByteArray, val guessed: Boolean)

/**
 * قراءة/كتابة نصوص المحرر بترميزها الأصلي: BOM أولاً، ثم UTF-8 الصارم، ثم Windows-1256
 * (الترميز العربي القديم الشائع) كتخمين يُعلَّم [DecodedText.guessed]، وأخيراً ISO-8859-1 الذي يقبل أي بايت.
 */
object TextFile {
    private val FALLBACKS: List<Charset> = listOfNotNull(
        runCatching { Charset.forName("windows-1256") }.getOrNull(),
        Charsets.ISO_8859_1
    )

    fun decode(bytes: ByteArray): DecodedText {
        val bom = TextCodec.bomOf(bytes)
        if (bom.isNotEmpty()) {
            val cs = TextCodec.charsetFromBom(bom)
            return DecodedText(TextCodec.decodeLenient(bytes.copyOfRange(bom.size, bytes.size), cs), cs, bom, false)
        }
        try {
            return DecodedText(TextCodec.decodeStrict(bytes, Charsets.UTF_8), Charsets.UTF_8, ByteArray(0), false)
        } catch (_: CharacterCodingException) {
        }
        for (cs in FALLBACKS) {
            try {
                return DecodedText(TextCodec.decodeStrict(bytes, cs), cs, ByteArray(0), true)
            } catch (_: CharacterCodingException) {
            }
        }
        return DecodedText(TextCodec.decodeLenient(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1, ByteArray(0), true)
    }

    /** يرمّز بالترميز الأصلي مع إعادة BOM؛ يرمي CharacterCodingException إن وُجد محرف لا يمثله الترميز. */
    @Throws(CharacterCodingException::class)
    fun encode(text: String, charset: Charset, bom: ByteArray): ByteArray = bom + TextCodec.encodeStrict(text, charset)
}
