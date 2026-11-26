package org.openfilemanager.core.pdf

import java.nio.ByteBuffer

/** Raw PDF value types (everything else is Boolean, Long, Double, List<Any?>, Map<String, Any?> or null). */
internal class PdfName(val v: String) {
    override fun equals(other: Any?) = other is PdfName && other.v == v
    override fun hashCode() = v.hashCode()
    override fun toString() = "/$v"
}

internal class PdfString(val bytes: ByteArray)
internal data class PdfRef(val num: Int, val gen: Int)
internal class PdfKeyword(val v: String) {
    override fun toString() = v
}

/** Stream = dictionary + where its raw (still filtered) bytes live in the file buffer. */
internal class PdfStream(val dict: Map<String, Any?>, val start: Int, val length: Int, val buf: ByteBuffer)

/**
 * Minimal PDF tokenizer/parser over a ByteBuffer. Used both for file structure and for content streams
 * ([allowRefs] = false for the latter, where "1 0 R" can never occur).
 */
internal class PdfLexer(val b: ByteBuffer, var pos: Int = 0, private val allowRefs: Boolean = true) {

    private val limit: Int = b.limit()

    private fun at(i: Int): Int = b.get(i).toInt() and 0xff

    fun skipWs() {
        while (pos < limit) {
            val c = at(pos)
            if (isWs(c)) pos++
            else if (c == '%'.code) {
                while (pos < limit && at(pos) != '\n'.code && at(pos) != '\r'.code) pos++
            } else break
        }
    }

    /** Reads one object; bare words come back as [PdfKeyword]; null at end of data. */
    fun readValue(depth: Int = 0): Any? {
        if (depth > 64) throw PdfFormatException("nesting too deep")
        skipWs()
        if (pos >= limit) return null
        val c = at(pos)
        return when {
            c == '/'.code -> readName()
            c == '('.code -> readLiteralString()
            c == '<'.code ->
                if (pos + 1 < limit && at(pos + 1) == '<'.code) readDict(depth) else readHexString()
            c == '['.code -> readArray(depth)
            c == ']'.code || c == '>'.code || c == ')'.code || c == '{'.code || c == '}'.code -> {
                // Stray delimiter: surface it as a keyword so callers can stop on it.
                pos++
                if (c == '>'.code && pos < limit && at(pos) == '>'.code) { pos++; PdfKeyword(">>") } else PdfKeyword(c.toChar().toString())
            }
            c == '+'.code || c == '-'.code || c == '.'.code || (c >= '0'.code && c <= '9'.code) -> readNumberOrRef()
            else -> readKeyword()
        }
    }

    private fun readName(): PdfName {
        pos++ // '/'
        val sb = StringBuilder()
        while (pos < limit) {
            if (sb.length > MAX_TOKEN_CHARS) throw PdfFormatException("token too long")
            val c = at(pos)
            if (isWs(c) || isDelim(c)) break
            if (c == '#'.code && pos + 2 < limit && hexVal(at(pos + 1)) >= 0 && hexVal(at(pos + 2)) >= 0) {
                sb.append(((hexVal(at(pos + 1)) shl 4) or hexVal(at(pos + 2))).toChar())
                pos += 3
            } else {
                sb.append(c.toChar())
                pos++
            }
        }
        return PdfName(sb.toString())
    }

    private fun readKeyword(): PdfKeyword {
        val start = pos
        while (pos < limit) {
            val c = at(pos)
            if (isWs(c) || isDelim(c)) break
            pos++
        }
        if (pos - start > MAX_TOKEN_CHARS) throw PdfFormatException("token too long")
        if (pos == start) pos++ // never loop forever on a weird byte
        val sb = StringBuilder(pos - start)
        for (i in start until pos) sb.append(at(i).toChar())
        return PdfKeyword(sb.toString())
    }

    private fun readNumberOrRef(): Any? {
        val start = pos
        var real = false
        if (at(pos) == '+'.code || at(pos) == '-'.code) pos++
        // Some producers write "--5"; treat extra signs as noise.
        while (pos < limit && (at(pos) == '-'.code || at(pos) == '+'.code)) pos++
        val digitsStart = pos
        while (pos < limit) {
            val c = at(pos)
            if (c == '.'.code) { real = true; pos++ }
            else if (c >= '0'.code && c <= '9'.code) pos++
            else break
        }
        if (pos == digitsStart) {
            // Not a number after all ("-" or "." alone): treat as keyword text.
            pos = start
            return readKeyword()
        }
        if (pos - start > MAX_TOKEN_CHARS) throw PdfFormatException("token too long")
        val text = StringBuilder()
        var negative = false
        for (i in start until pos) {
            val ch = at(i).toChar()
            if (ch == '-') negative = true else if (ch != '+') text.append(ch)
        }
        val s = text.toString()
        if (real) {
            val d = s.toDoubleOrNull() ?: 0.0
            return if (negative) -d else d
        }
        val n = s.toLongOrNull() ?: return 0L
        val value = if (negative) -n else n
        if (!allowRefs || negative || value > Int.MAX_VALUE) return value
        // Look ahead for "<gen> R".
        val save = pos
        skipWs()
        if (pos < limit && at(pos) in '0'.code..'9'.code) {
            var p = pos
            var gen = 0L
            while (p < limit && at(p) in '0'.code..'9'.code && gen < 100000) { gen = gen * 10 + (at(p) - '0'.code); p++ }
            var q = p
            while (q < limit && isWs(at(q))) q++
            if (q > p && q < limit && at(q) == 'R'.code && (q + 1 >= limit || isWs(at(q + 1)) || isDelim(at(q + 1)))) {
                pos = q + 1
                return PdfRef(value.toInt(), gen.toInt())
            }
        }
        pos = save
        return value
    }

    private fun readLiteralString(): PdfString {
        pos++ // '('
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        while (pos < limit) {
            if (out.size() > MAX_TOKEN_BYTES) throw PdfFormatException("token too long")
            val c = at(pos++)
            when {
                c == '\\'.code -> {
                    if (pos >= limit) break
                    val e = at(pos++)
                    when (e) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        '('.code, ')'.code, '\\'.code -> out.write(e)
                        '\r'.code -> if (pos < limit && at(pos) == '\n'.code) pos++
                        '\n'.code -> {}
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            var n = 1
                            while (n < 3 && pos < limit && at(pos) in '0'.code..'7'.code) { v = v * 8 + (at(pos) - '0'.code); pos++; n++ }
                            out.write(v and 0xff)
                        }
                        else -> out.write(e)
                    }
                }
                c == '('.code -> { depth++; out.write(c) }
                c == ')'.code -> { depth--; if (depth == 0) return PdfString(out.toByteArray()); out.write(c) }
                else -> out.write(c)
            }
        }
        return PdfString(out.toByteArray())
    }

    private fun readHexString(): PdfString {
        pos++ // '<'
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (pos < limit) {
            if (out.size() > MAX_TOKEN_BYTES) throw PdfFormatException("token too long")
            val c = at(pos++)
            if (c == '>'.code) break
            val v = hexVal(c)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return PdfString(out.toByteArray())
    }

    private fun readArray(depth: Int): List<Any?> {
        pos++ // '['
        val list = ArrayList<Any?>()
        while (true) {
            skipWs()
            if (pos >= limit) break
            if (at(pos) == ']'.code) { pos++; break }
            val v = readValue(depth + 1)
            if (v is PdfKeyword) {
                if (v.v == ">>" || v.v == "endobj") break
                if (v.v == "null") { list.add(null); continue }
                if (v.v == "true") { list.add(true); continue }
                if (v.v == "false") { list.add(false); continue }
                // content streams put operators inside arrays only when malformed: ignore
                continue
            }
            list.add(v)
        }
        return list
    }

    private fun readDict(depth: Int): Map<String, Any?> {
        pos += 2 // '<<'
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            skipWs()
            if (pos >= limit) break
            if (at(pos) == '>'.code && pos + 1 < limit && at(pos + 1) == '>'.code) { pos += 2; break }
            val key = readValue(depth + 1)
            if (key !is PdfName) {
                if (key is PdfKeyword && (key.v == "endobj" || key.v == "stream")) { pos -= key.v.length; break }
                if (key == null) break
                continue
            }
            val value = readValue(depth + 1)
            if (value is PdfKeyword) {
                when (value.v) {
                    "null" -> map[key.v] = null
                    "true" -> map[key.v] = true
                    "false" -> map[key.v] = false
                    ">>" -> { break }
                    "endobj", "stream" -> { pos -= value.v.length; break }
                }
                continue
            }
            map[key.v] = value
        }
        return map
    }

    /** Reads a non-negative integer token at the current position (used by xref tables). */
    fun readInt(): Long {
        skipWs()
        val start = pos
        var n = 0L
        while (pos < limit && at(pos) in '0'.code..'9'.code) {
            n = n * 10 + (at(pos) - '0'.code)
            pos++
            if (n > Long.MAX_VALUE / 20) break
        }
        if (pos == start) throw PdfFormatException("integer expected at $start")
        return n
    }

    fun startsWith(word: String): Boolean {
        skipWs()
        if (pos + word.length > limit) return false
        for (i in word.indices) if (at(pos + i) != word[i].code) return false
        return true
    }

    companion object {
        /** (ميزانية) طول رمز واحد: الرموز الحقيقية قصيرة، ومدخل عدائي كان يستطيع
         *  تحويل تيار 64 م.ب كله سلسلة واحدة فتتضاعف الذاكرة (UTF-16). */
        private const val MAX_TOKEN_CHARS = 1 shl 20

        /** مثله بالبايتات للسلاسل الخام. */
        private const val MAX_TOKEN_BYTES = 1 shl 20

        fun isWs(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0
        fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
        fun hexVal(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}

open class PdfFormatException(message: String) : Exception(message)

/** The PDF is encrypted; text/outline are not available (the platform renderer may still need a password). */
class PdfEncryptedException : Exception("encrypted PDF")
