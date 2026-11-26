package org.openfilemanager.core.pdf

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Walks a page content stream and collects the shown text. Glyph advances come from the font's width tables
 * where present, so word gaps are measured (a gap wider than ~0.2 em is a space) instead of guessed; fonts
 * without width tables fall back to "a repositioning means a word break".
 */
internal class PdfContentInterpreter(private val doc: PdfTextDocument) {

    val runs = ArrayList<PdfTextRun>()
    var skippedUnmapped = false
        private set

    private val sb = StringBuilder()
    private val MAX_GS_STACK = 256
    private var budget = 3_000_000 // operators per page: stops pathological streams

    /** (ميزانية) بايتات XObjects النمطية (Do) المفكوكة في هذه الصفحة: حلقة
     *  Do متكررة كانت تفك تياراً 64 م.ب في كل رمز — ميزانية الرموز وحدها لم
     *  تُقيّد البايتات. التجاوز يوقف المعترض بأدب (نص جزئي) لا باستثناء. */
    private var formDecodeBytes = 0

    // Where the previous string ended (user space); NaN end = unknown (font had no widths).
    private var haveLast = false
    private var endX = 0.0
    private var endY = 0.0
    private var endKnown = false
    private var pendingMove = false

    private class Gs(
        var ctm: DoubleArray, var font: PdfFont?, var size: Double, var leading: Double,
        var tc: Double, var tw: Double, var th: Double
    ) {
        fun copy() = Gs(ctm, font, size, leading, tc, tw, th)
    }

    fun text(): String = sb.toString().trimEnd()

    fun run(data: ByteArray, resources: Any?, depth: Int, baseCtm: DoubleArray) {
        if (depth > 3) return
        val fonts = doc.fontsOf(resources)
        val lx = PdfLexer(ByteBuffer.wrap(data), 0, allowRefs = false)
        val operands = ArrayList<Any?>()
        val stack = ArrayList<Gs>()
        var gs = Gs(baseCtm, null, 0.0, 0.0, 0.0, 0.0, 1.0)
        var tm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        var tlm = tm

        fun combined(): DoubleArray = mul(tm, gs.ctm)

        fun advance(tx: Double) {
            tm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, tx, 0.0), tm)
        }

        fun markEnd(known: Boolean) {
            val c = combined()
            endX = c[4]; endY = c[5]; endKnown = known; haveLast = true
        }

        fun show(raw: ByteArray, continuation: Boolean) {
            val f = gs.font
            if (f == null || !f.usable) { if (raw.isNotEmpty()) skippedUnmapped = true; return }
            // (ميزانية) سقف أحرف الصفحة يسري هنا أيضاً: مسار الالتحاق TJ كان
            // يُلحق النص مباشرة بلا فحص فيتجاوز MAX_CHARS بمصفوفة واحدة ضخمة.
            if (sb.length > MAX_CHARS) return
            val s = f.decode(raw)
            val c = combined()
            if (s.isNotEmpty()) {
                if (continuation) sb.append(s)
                else {
                    val size = gs.size * hypot(c[2], c[3])
                    emit(s, c, size)
                }
            }
            val m = f.measure(raw)
            if (m != null) {
                advance((m[0] * gs.size + m[1] * gs.tc + m[2] * gs.tw) * gs.th)
                markEnd(true)
            } else {
                markEnd(false)
            }
        }

        fun moveLine(tx: Double, ty: Double) {
            tlm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, tx, ty), tlm)
            tm = tlm
            pendingMove = true
        }

        while (budget-- > 0) {
            val v = lx.readValue() ?: break
            if (v !is PdfKeyword) { operands.add(v); if (operands.size > 512) operands.clear(); continue }
            when (v.v) {
                "q" -> {
                    // Bound the saved-state stack; stop politely (partial text) like the other budgets.
                    if (stack.size >= MAX_GS_STACK) break
                    stack.add(gs.copy())
                }
                "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1)
                "cm" -> nums(operands, 6)?.let { gs.ctm = mul(it, gs.ctm) }
                "BT" -> { tm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0); tlm = tm }
                "Tf" -> {
                    val name = (operands.getOrNull(operands.size - 2) as? PdfName)?.v
                    val size = (operands.lastOrNull() as? Number)?.toDouble()
                    if (name != null && size != null) {
                        gs.font = fonts[name]?.let { doc.fontFor(it) } ?: PdfFont.UNMAPPED
                        gs.size = size
                    }
                }
                "TL" -> (operands.lastOrNull() as? Number)?.let { gs.leading = it.toDouble() }
                "Tc" -> (operands.lastOrNull() as? Number)?.let { gs.tc = it.toDouble() }
                "Tw" -> (operands.lastOrNull() as? Number)?.let { gs.tw = it.toDouble() }
                "Tz" -> (operands.lastOrNull() as? Number)?.let { gs.th = it.toDouble() / 100.0 }
                "Tm" -> nums(operands, 6)?.let { tm = it; tlm = it; pendingMove = true }
                "Td" -> nums(operands, 2)?.let { moveLine(it[0], it[1]) }
                "TD" -> nums(operands, 2)?.let { gs.leading = -it[1]; moveLine(it[0], it[1]) }
                "T*" -> moveLine(0.0, -gs.leading)
                "Tj" -> (operands.lastOrNull() as? PdfString)?.let { show(it.bytes, false) }
                "'" -> { moveLine(0.0, -gs.leading); (operands.lastOrNull() as? PdfString)?.let { show(it.bytes, false) } }
                "\"" -> { moveLine(0.0, -gs.leading); (operands.lastOrNull() as? PdfString)?.let { show(it.bytes, false) } }
                "TJ" -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = operands.lastOrNull() as? List<Any?>
                    if (arr != null) {
                        var first = true
                        // (ميزانية) عناصر المصفوفة عملٌ لكل عنصر: كانت المصفوفة
                        // رمزاً واحداً لميزانية الرموز فتُفرَّغ بلا عد.
                        budget -= arr.size
                        for (e in arr) {
                            when (e) {
                                is PdfString -> { show(e.bytes, !first); first = false }
                                is Number -> {
                                    val adj = e.toDouble()
                                    if (!first && adj < -SPACE_ADJUST && sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                                    advance(-adj / 1000.0 * gs.size * gs.th)
                                    markEnd(endKnown)
                                }
                            }
                        }
                    }
                }
                "Do" -> {
                    val name = (operands.lastOrNull() as? PdfName)?.v
                    val x = name?.let { doc.xobject(resources, it) }
                    if (x != null && (x.dict["Subtype"] as? PdfName)?.v == "Form") {
                        val m = doc.numbers(x.dict["Matrix"])?.takeIf { it.size == 6 }?.toDoubleArray()
                        val inner = doc.dictOf(x.dict["Resources"]) ?: resources
                        val data2 = runCatching { doc.decodeStream(x) }.getOrNull()
                        if (data2 != null) {
                            formDecodeBytes += data2.size
                            if (formDecodeBytes > MAX_FORM_DECODE_BYTES) return
                            run(data2, inner, depth + 1, if (m != null) mul(m, gs.ctm) else gs.ctm)
                        }
                    }
                }
                "BI" -> skipInlineImage(lx)
            }
            operands.clear()
        }
    }

    /** [c] = text-to-user-space matrix at the start of the string. */
    private fun emit(s: String, c: DoubleArray, size: Double) {
        if (sb.length > MAX_CHARS) return
        val x = c[4]
        val y = c[5]
        if (haveLast) {
            val ux = c[0]; val uy = c[1]
            val norm = hypot(ux, uy).let { if (it == 0.0) 1.0 else it }
            val dx = x - endX
            val dy = y - endY
            val along = (dx * ux + dy * uy) / norm
            val across = (-dx * uy + dy * ux) / norm
            val lineGap = max(1.0, size * 0.5)
            if (abs(across) > lineGap) newline()
            else if (endKnown) {
                if (along > size * SPACE_EM && sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            } else if (pendingMove && sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
        }
        pendingMove = false
        runs.add(PdfTextRun(sb.length, x, y, size))
        sb.append(s)
    }

    private fun newline() {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
    }

    private fun skipInlineImage(lx: PdfLexer) {
        // BI <dict entries> ID <binary> EI
        while (true) {
            val v = lx.readValue() ?: return
            if (v is PdfKeyword && v.v == "ID") break
        }
        val b = lx.b
        var p = lx.pos + 1
        while (p + 2 < b.limit()) {
            if (b.get(p).toInt() == 'E'.code && b.get(p + 1).toInt() == 'I'.code &&
                PdfLexer.isWs(b.get(p - 1).toInt() and 0xff) &&
                (p + 2 >= b.limit() || PdfLexer.isWs(b.get(p + 2).toInt() and 0xff))
            ) { lx.pos = p + 2; return }
            p++
        }
        lx.pos = b.limit()
    }

    private fun nums(ops: List<Any?>, n: Int): DoubleArray? {
        if (ops.size < n) return null
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = (ops[ops.size - n + i] as? Number)?.toDouble() ?: return null
        return out
    }

    /** a × b for PDF matrices [a b c d e f] (row vectors: result applies a first, then b). */
    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
        a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]
    )

    private companion object {
        /** TJ adjustment (thousandths of an em) beyond which a gap is a word space. */
        const val SPACE_ADJUST = 200.0
        /** Gap (in em) between measured end of the previous string and the next start that counts as a space. */
        const val SPACE_EM = 0.2
        const val MAX_CHARS = 600_000

        /** (ميزانية) سقف فك XObjects النمطية لكل صفحة (نفس سقف محتوى الصفحة). */
        const val MAX_FORM_DECODE_BYTES = 32 shl 20
    }
}
