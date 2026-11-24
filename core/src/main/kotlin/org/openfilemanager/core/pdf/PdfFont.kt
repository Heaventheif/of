package org.openfilemanager.core.pdf

import java.nio.ByteBuffer
import java.nio.charset.Charset

/** Maps the bytes of a shown string to Unicode text. [usable] = false: we cannot decode this font reliably. */
internal class PdfFont(
    val usable: Boolean,
    private val codeBytes: Int,
    private val cmap: Map<Int, String>?,
    private val simple: Array<String?>?,
    /** Glyph advance per character code in 1/1000 em; null when the font gives no widths. */
    private val widths: ((Int) -> Double)? = null
) {
    /** [total advance in em, glyph count, space-glyph count] of a shown string, or null when widths are unknown. */
    fun measure(b: ByteArray): DoubleArray? {
        val w = widths ?: return null
        var sum = 0.0
        var glyphs = 0
        var spaces = 0
        if (codeBytes == 2) {
            var i = 0
            while (i + 1 < b.size) {
                sum += w(((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)); glyphs++; i += 2
            }
        } else {
            for (x in b) {
                val c = x.toInt() and 0xff
                sum += w(c); glyphs++
                if (c == 32) spaces++
            }
        }
        return doubleArrayOf(sum / 1000.0, glyphs.toDouble(), spaces.toDouble())
    }

    fun decode(b: ByteArray): String {
        if (!usable) return ""
        val sb = StringBuilder(b.size)
        if (codeBytes == 2) {
            var i = 0
            while (i + 1 < b.size) {
                val code = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
                cmap?.get(code)?.let { sb.append(it) }
                i += 2
            }
        } else {
            for (x in b) {
                val code = x.toInt() and 0xff
                val s = cmap?.get(code) ?: simple?.get(code)
                if (s != null) sb.append(s)
            }
        }
        return sb.toString()
    }

    companion object {
        val UNMAPPED = PdfFont(false, 1, null, null)
    }
}

internal class PdfFontLoader(private val file: PdfFile) {

    fun load(d: Map<String, Any?>): PdfFont {
        val subtype = file.name(d["Subtype"])
        // (ميزانية) تيار ToUnicode حقيقي ≤ بضع مئات ك.ب؛ السقف 8 م.ب يكفي
        // أي CMap واقعي ويمنع تيار 64 م.ب من احتلال الذاكرة كاملة قبل الفحص.
        val toUni = (file.resolve(d["ToUnicode"]) as? PdfStream)?.let {
            runCatching { parseToUnicode(file.decode(it, maxOutput = 8 * 1024 * 1024)) }.getOrNull()
        }
        if (subtype == "Type0") {
            // Composite font: without a ToUnicode CMap the codes are glyph ids we cannot translate.
            if (toUni == null || toUni.map.isEmpty()) return PdfFont.UNMAPPED
            return PdfFont(true, if (toUni.codeBytes == 1) 1 else 2, toUni.map, null, cidWidths(d))
        }
        if (subtype == "Type3" && toUni == null) {
            return PdfFont(true, 1, null, baseTable("StandardEncoding")) // best effort: usually plain ASCII
        }
        val table = buildSimpleTable(d)
        return PdfFont(true, 1, toUni?.map, table, simpleWidths(d))
    }

    private fun simpleWidths(d: Map<String, Any?>): ((Int) -> Double)? {
        val arr = file.array(d["Widths"])?.map { file.num(it) ?: 0.0 } ?: return null
        val first = file.int(d["FirstChar"]) ?: return null
        val missing = file.num(file.dict(d["FontDescriptor"])?.get("MissingWidth")) ?: 0.0
        return { c -> if (c >= first && c - first < arr.size) arr[c - first] else missing }
    }

    private fun cidWidths(d: Map<String, Any?>): ((Int) -> Double)? {
        val desc = file.dict(file.array(d["DescendantFonts"])?.firstOrNull()) ?: return null
        val dw = file.num(desc["DW"]) ?: 1000.0
        val map = HashMap<Int, Double>()
        val w = file.array(desc["W"]) ?: return { dw }
        var i = 0
        while (i < w.size && map.size < 70_000) {
            val c = file.int(w[i]) ?: break
            val next = file.resolve(w.getOrNull(i + 1))
            if (next is List<*>) {
                for ((k, x) in next.withIndex()) map[c + k] = file.num(x) ?: dw
                i += 2
            } else {
                val last = file.int(next) ?: break
                val width = file.num(w.getOrNull(i + 2)) ?: dw
                if (last - c in 0..0xFFFF) for (k in c..last) map[k] = width
                i += 3
            }
        }
        return { c -> map[c] ?: dw }
    }

    // ---- simple fonts: base encoding + /Differences ---------------------------------------------------------

    private fun buildSimpleTable(d: Map<String, Any?>): Array<String?> {
        val enc = file.resolve(d["Encoding"])
        var baseName: String? = null
        var diffs: List<Any?>? = null
        when (enc) {
            is PdfName -> baseName = enc.v
            is Map<*, *> -> {
                baseName = file.name(enc["BaseEncoding"])
                diffs = file.array(enc["Differences"])
            }
        }
        val table = baseTable(baseName)
        if (diffs != null) {
            var code = 0
            for (item in diffs) {
                when (val r = file.resolve(item)) {
                    is Number -> code = r.toInt()
                    is PdfName -> {
                        if (code in 0..255) table[code] = glyphToString(r.v)
                        code++
                    }
                }
            }
        }
        return table
    }

    private fun baseTable(name: String?): Array<String?> {
        val cs: Charset = when (name) {
            "MacRomanEncoding" -> runCatching { Charset.forName("x-MacRoman") }.getOrDefault(Charsets.ISO_8859_1)
            "WinAnsiEncoding" -> runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
            else -> Charsets.ISO_8859_1 // StandardEncoding and unspecified: ASCII range matches, good enough for search
        }
        val t = arrayOfNulls<String>(256)
        for (i in 32 until 256) {
            if (name == null || name == "StandardEncoding") {
                if (i in 127..160) continue
            }
            val s = String(byteArrayOf(i.toByte()), cs)
            if (s.isNotEmpty() && s != "�") t[i] = s
        }
        t[0x0A] = " "; t[0x0D] = " "; t[0x09] = " "
        return t
    }

    // ---- ToUnicode CMap ---------------------------------------------------------------------------------------

    internal class CMap(val map: HashMap<Int, String>, val codeBytes: Int)

    private fun parseToUnicode(data: ByteArray): CMap {
        val lx = PdfLexer(ByteBuffer.wrap(data), 0, allowRefs = false)
        val map = HashMap<Int, String>()
        var codeBytes = 0
        // (ميزانية) قائمة الرموز المؤقتة محدودة: كانت تجمع بلا حد بين begin/end
        // فيستهلك المدخل كله ذاكرة قبل أول فحص.
        val tokens = ArrayList<Any?>(32)
        var mode = ""
        var guard = 0
        while (guard++ < 2_000_000) {
            val v = lx.readValue() ?: break
            if (v is PdfKeyword) {
                when (v.v) {
                    "begincodespacerange", "beginbfchar", "beginbfrange" -> { mode = v.v; tokens.clear() }
                    "endcodespacerange" -> {
                        for (t in tokens) if (t is PdfString) codeBytes = maxOf(codeBytes, t.bytes.size)
                        mode = ""
                    }
                    "endbfchar" -> {
                        var i = 0
                        while (i + 1 < tokens.size) {
                            val src = tokens[i] as? PdfString
                            val dst = tokens[i + 1]
                            if (src != null && map.size < MAX_CMAP_ENTRIES) {
                                dstString(dst)?.let { map[codeOf(src.bytes)] = it }
                            }
                            i += 2
                        }
                        mode = ""
                    }
                    "endbfrange" -> {
                        var i = 0
                        while (i + 2 < tokens.size) {
                            val lo = tokens[i] as? PdfString
                            val hi = tokens[i + 1] as? PdfString
                            val dst = tokens[i + 2]
                            if (lo != null && hi != null && map.size < MAX_CMAP_ENTRIES) {
                                val a = codeOf(lo.bytes)
                                val b = codeOf(hi.bytes)
                                if (b >= a && b - a <= 0xFFFF) {
                                    if (dst is List<*>) {
                                        for (k in 0..(b - a)) {
                                            if (map.size >= MAX_CMAP_ENTRIES) break
                                            dstString(dst.getOrNull(k))?.let { map[a + k] = it }
                                        }
                                    } else {
                                        val base = dstString(dst)
                                        if (base != null && base.isNotEmpty()) {
                                            for (k in 0..(b - a)) {
                                                if (map.size >= MAX_CMAP_ENTRIES) break
                                                val last = base[base.length - 1] + k
                                                map[a + k] = base.substring(0, base.length - 1) + last
                                            }
                                        }
                                    }
                                }
                            }
                            i += 3
                        }
                        mode = ""
                    }
                    else -> {}
                }
            } else if (mode.isNotEmpty()) {
                if (tokens.size < MAX_CMAP_TOKENS) tokens.add(v)
            }
        }
        return CMap(map, if (codeBytes == 1) 1 else 2)
    }

    private fun codeOf(b: ByteArray): Int {
        var v = 0
        for (x in b) v = (v shl 8) or (x.toInt() and 0xff)
        return v
    }

    private fun dstString(v: Any?): String? = when (v) {
        is PdfString -> if (v.bytes.size >= 2) String(v.bytes, Charsets.UTF_16BE) else if (v.bytes.size == 1) String(v.bytes, Charsets.ISO_8859_1) else null
        is PdfName -> glyphToString(v.v)
        else -> null
    }

    companion object {
        /** (ميزانية) سقف مداخل CMap: مجال الرموز ذات البايتين كله = 65536. */
        private const val MAX_CMAP_ENTRIES = 65_536

        /** (ميزانية) سقف الرموز المؤقتة بين begin/end في ToUnicode. */
        private const val MAX_CMAP_TOKENS = 8_192
        private val GLYPHS: Map<String, String> = mapOf(
            "space" to " ", "nbspace" to " ", "hyphen" to "-", "minus" to "-", "period" to ".", "comma" to ",", "colon" to ":",
            "semicolon" to ";", "exclam" to "!", "question" to "?", "quotesingle" to "'", "quoteright" to "’",
            "quoteleft" to "‘", "quotedbl" to "\"", "quotedblleft" to "“", "quotedblright" to "”",
            "parenleft" to "(", "parenright" to ")", "bracketleft" to "[", "bracketright" to "]", "braceleft" to "{",
            "braceright" to "}", "slash" to "/", "backslash" to "\\", "underscore" to "_", "ampersand" to "&",
            "asterisk" to "*", "at" to "@", "numbersign" to "#", "dollar" to "$", "percent" to "%", "plus" to "+",
            "equal" to "=", "less" to "<", "greater" to ">", "bar" to "|", "asciitilde" to "~", "asciicircum" to "^",
            "grave" to "`", "endash" to "–", "emdash" to "—", "bullet" to "•", "ellipsis" to "…",
            "fi" to "fi", "fl" to "fl", "ff" to "ff", "ffi" to "ffi", "ffl" to "ffl", "copyright" to "©",
            "registered" to "®", "degree" to "°", "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "germandbls" to "ß", "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á",
            "ccedilla" to "ç", "ntilde" to "ñ", "odieresis" to "ö", "udieresis" to "ü", "adieresis" to "ä"
        )

        fun glyphToString(name: String): String? {
            GLYPHS[name]?.let { return it }
            if (name.length == 1) return name
            if (name.startsWith("uni") && name.length >= 7) name.substring(3, 7).toIntOrNull(16)?.let { return it.toChar().toString() }
            if (name.startsWith("u") && name.length in 5..7) name.substring(1).toIntOrNull(16)?.let { return String(Character.toChars(it)) }
            return null
        }
    }
}
