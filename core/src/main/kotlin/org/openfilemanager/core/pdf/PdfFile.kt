package org.openfilemanager.core.pdf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater

private class XEntry(val type: Int, val a: Long, val b: Int)

/** Low-level PDF file access: xref (tables, streams, hybrids), objects, object streams, stream filters. */
internal class PdfFile(val buf: ByteBuffer, val budget: PdfResourceBudget = PdfResourceBudget()) {

    private val xref = HashMap<Int, XEntry>()
    private val trailer = HashMap<String, Any?>()

    /**
     * (ميزانية) مخزن الكائنات LRU بسقف [PdfResourceBudget.maxCachedObjects]:
     * كان HashMap بلا إخلاء فيحتفظ بكل جسم حُلّ عمر المستند كله (ذاكرة بلا
     * سقف من مستند عدائي). سلوك القراءة نفسه؛ الأقدم يُخلّى فقط.
     */
    private val cache = object : LinkedHashMap<Int, Any?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Any?>?): Boolean =
            size > budget.maxCachedObjects
    }
    private val objStmCache = object : LinkedHashMap<Int, Pair<List<Pair<Int, Int>>, ByteBuffer>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<List<Pair<Int, Int>>, ByteBuffer>>?) = size > 4
    }
    private val loading = HashSet<Int>()

    val isEncrypted: Boolean get() = trailer["Encrypt"] != null

    init {
        val start = findStartXref()
        readXrefChain(start)
        if (trailer["Root"] == null) throw PdfFormatException("no Root in trailer")
    }

    // ---- public-ish helpers -------------------------------------------------------------------------------

    fun resolve(v: Any?): Any? {
        var cur = v
        var hops = 0
        while (cur is PdfRef && hops++ < 32) cur = obj(cur.num)
        return if (cur is PdfRef) null else cur
    }

    @Suppress("UNCHECKED_CAST")
    fun dict(v: Any?): Map<String, Any?>? = when (val r = resolve(v)) {
        is Map<*, *> -> r as Map<String, Any?>
        is PdfStream -> r.dict
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun array(v: Any?): List<Any?>? = resolve(v) as? List<Any?>

    fun name(v: Any?): String? = (resolve(v) as? PdfName)?.v

    fun num(v: Any?): Double? = (resolve(v) as? Number)?.toDouble()

    fun int(v: Any?): Int? = (resolve(v) as? Number)?.toInt()

    fun root(): Map<String, Any?> = dict(trailer["Root"]) ?: throw PdfFormatException("bad Root")

    // ---- startxref / xref ---------------------------------------------------------------------------------

    private fun findStartXref(): Int {
        val lim = buf.limit()
        val from = maxOf(0, lim - 2048)
        val key = "startxref".toByteArray(Charsets.ISO_8859_1)
        var i = lim - key.size
        while (i >= from) {
            var ok = true
            for (k in key.indices) if (buf.get(i + k) != key[k]) { ok = false; break }
            if (ok) {
                val lx = PdfLexer(buf, i + key.size)
                return lx.readInt().toInt()
            }
            i--
        }
        throw PdfFormatException("startxref not found")
    }

    private fun readXrefChain(first: Int) {
        val seen = HashSet<Int>()
        var off = first
        while (off in 0 until buf.limit() && seen.add(off)) {
            val lx = PdfLexer(buf, off)
            val prev: Int
            if (lx.startsWith("xref")) {
                lx.pos += 4
                val tr = readXrefTable(lx)
                tr.forEach { (k, v) -> if (!trailer.containsKey(k)) trailer[k] = v }
                val stmOff = (tr["XRefStm"] as? Number)?.toInt()
                if (stmOff != null && seen.add(stmOff)) runCatching { readXrefStream(stmOff) }
                prev = (tr["Prev"] as? Number)?.toInt() ?: -1
            } else {
                val d = readXrefStream(off)
                d.forEach { (k, v) -> if (!trailer.containsKey(k)) trailer[k] = v }
                prev = (d["Prev"] as? Number)?.toInt() ?: -1
            }
            off = prev
        }
    }

    private fun readXrefTable(lx: PdfLexer): Map<String, Any?> {
        while (true) {
            lx.skipWs()
            if (lx.startsWith("trailer")) { lx.pos += 7; break }
            val firstNum = lx.readInt().toInt()
            val count = lx.readInt().toInt()
            for (i in 0 until count) {
                val offset = lx.readInt()
                val gen = lx.readInt().toInt()
                lx.skipWs()
                val kind = buf.get(lx.pos).toInt().toChar()
                lx.pos++
                if (kind == 'n' && offset > 0) xref.putIfAbsent(firstNum + i, XEntry(1, offset, gen))
                else if (kind == 'f') xref.putIfAbsent(firstNum + i, XEntry(0, 0, gen))
                // (ميزانية) سقف مداخل xref قبل نمو الخريطة بلا حد من ملف عدائي.
                if (xref.size > budget.maxXrefEntries) throw PdfFormatException("xref table too large")
            }
        }
        @Suppress("UNCHECKED_CAST")
        return (PdfLexer(buf, lx.pos).readValue() as? Map<String, Any?>) ?: throw PdfFormatException("bad trailer")
    }

    private fun readXrefStream(offset: Int): Map<String, Any?> {
        val parsed = parseIndirectAt(offset, expectNum = null) as? PdfStream ?: throw PdfFormatException("xref stream expected")
        val d = parsed.dict
        val w = (d["W"] as? List<*>)?.map { (it as? Number)?.toInt() ?: 0 } ?: throw PdfFormatException("bad W")
        if (w.size < 3) throw PdfFormatException("bad W")
        val size = (d["Size"] as? Number)?.toInt() ?: 0
        val index = (d["Index"] as? List<*>)?.map { (it as? Number)?.toInt() ?: 0 } ?: listOf(0, size)
        val data = decode(parsed)
        var p = 0
        val rowLen = w[0] + w[1] + w[2]
        if (rowLen <= 0) throw PdfFormatException("bad W")
        fun field(len: Int, default: Long): Long {
            if (len == 0) return default
            var v = 0L
            for (k in 0 until len) v = (v shl 8) or (data[p + k].toLong() and 0xff)
            p += len
            return v
        }
        var i = 0
        while (i + 1 < index.size) {
            val start = index[i]
            val cnt = index[i + 1]
            for (k in 0 until cnt) {
                if (p + rowLen > data.size) break
                val type = field(w[0], 1).toInt()
                val f2 = field(w[1], 0)
                val f3 = field(w[2], 0).toInt()
                when (type) {
                    1 -> xref.putIfAbsent(start + k, XEntry(1, f2, f3))
                    2 -> xref.putIfAbsent(start + k, XEntry(2, f2, f3))
                    0 -> xref.putIfAbsent(start + k, XEntry(0, 0, 0))
                }
                // (ميزانية) تدفق xref بعدائي (W=[1,0,0]) يستطيع إدخال ملايين
                // المداخل من تيار 64 م.ب — السقف يوقف نمو الخريطة قبل استهلاك الذاكرة.
                if (xref.size > budget.maxXrefEntries) throw PdfFormatException("xref stream too large")
            }
            i += 2
        }
        return d
    }

    // ---- objects ------------------------------------------------------------------------------------------

    fun obj(num: Int): Any? {
        if (cache.containsKey(num)) return cache[num]
        val e = xref[num] ?: return null
        if (!loading.add(num)) return null // circular reference
        try {
            val v: Any? = when (e.type) {
                1 -> runCatching { parseIndirectAt(e.a.toInt(), num) }.getOrNull()
                2 -> runCatching { objectFromStream(e.a.toInt(), e.b, num) }.getOrNull()
                else -> null
            }
            // Streams are cheap descriptors (no decoded data), so caching them is fine too.
            cache[num] = v
            return v
        } finally {
            loading.remove(num)
        }
    }

    /** Parses "n g obj <value> [stream…]" at [offset]. */
    private fun parseIndirectAt(offset: Int, expectNum: Int?): Any? {
        if (offset < 0 || offset >= buf.limit()) throw PdfFormatException("bad offset")
        val lx = PdfLexer(buf, offset)
        val n = lx.readInt().toInt()
        lx.readInt()
        lx.skipWs()
        if (!lx.startsWith("obj")) throw PdfFormatException("obj expected")
        lx.pos += 3
        if (expectNum != null && n != expectNum) throw PdfFormatException("object number mismatch")
        val value = lx.readValue()
        if (value is Map<*, *>) {
            lx.skipWs()
            if (lx.startsWith("stream")) {
                lx.pos += 6
                // EOL after the keyword: CRLF or LF (a lone CR is tolerated).
                if (lx.pos < buf.limit() && buf.get(lx.pos).toInt() == '\r'.code) lx.pos++
                if (lx.pos < buf.limit() && buf.get(lx.pos).toInt() == '\n'.code) lx.pos++
                @Suppress("UNCHECKED_CAST")
                val d = value as Map<String, Any?>
                val start = lx.pos
                var len = (resolveLengthSafely(d["Length"]))
                if (len < 0 || !endstreamFollows(start + len)) len = scanEndstream(start) - start
                return PdfStream(d, start, maxOf(0, len), buf)
            }
        }
        return if (value is PdfKeyword) null else value
    }

    private fun resolveLengthSafely(v: Any?): Int {
        val r = when (v) {
            is Number -> v
            is PdfRef -> resolve(v) as? Number
            else -> null
        } ?: return -1
        return r.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun endstreamFollows(at: Int): Boolean {
        if (at < 0 || at > buf.limit()) return false
        var p = at
        while (p < buf.limit() && PdfLexer.isWs(buf.get(p).toInt() and 0xff)) p++
        val key = "endstream"
        if (p + key.length > buf.limit()) return false
        for (i in key.indices) if (buf.get(p + i).toInt() != key[i].code) return false
        return true
    }

    private fun scanEndstream(from: Int): Int {
        val key = "endstream".toByteArray(Charsets.ISO_8859_1)
        var i = from
        val last = buf.limit() - key.size
        while (i <= last) {
            if (buf.get(i) == key[0]) {
                var ok = true
                for (k in 1 until key.size) if (buf.get(i + k) != key[k]) { ok = false; break }
                if (ok) {
                    var e = i
                    // Drop the EOL that precedes the keyword.
                    if (e > from && buf.get(e - 1).toInt() == '\n'.code) e--
                    if (e > from && buf.get(e - 1).toInt() == '\r'.code) e--
                    return e
                }
            }
            i++
        }
        return buf.limit()
    }

    private fun objectFromStream(stmNum: Int, index: Int, wantedNum: Int): Any? {
        val entry = objStmCache[stmNum] ?: run {
            val s = obj(stmNum) as? PdfStream ?: return null
            val n = int(s.dict["N"]) ?: return null
            // (ميزانية) /N محكوم قبل أي تخصيص حرصي: قيمة عدائية كانت تطلب
            // ArrayList بسعة تصل Int.MAX قبل أول فحص.
            if (n < 0 || n > budget.maxObjectsPerStream) throw PdfFormatException("object stream N too large")
            val first = int(s.dict["First"]) ?: return null
            if (first < 0) throw PdfFormatException("object stream First negative")
            val data = decode(s)
            val bb = ByteBuffer.wrap(data)
            val lx = PdfLexer(bb, 0, allowRefs = false)
            val table = ArrayList<Pair<Int, Int>>(n)
            for (i in 0 until n) {
                val on = lx.readInt().toInt()
                val off = lx.readInt().toInt()
                if (off < 0) continue // إزاحة عدائية: يُتخطى المدخل لا يُفسد الجدول
                table.add(on to first + off)
            }
            val pair = table to bb
            objStmCache[stmNum] = pair
            pair
        }
        val (table, bb) = entry
        val slot = table.getOrNull(index)?.takeIf { it.first == wantedNum } ?: table.firstOrNull { it.first == wantedNum } ?: return null
        val lx = PdfLexer(bb, slot.second)
        val v = lx.readValue()
        return if (v is PdfKeyword) null else v
    }

    // ---- stream decoding ----------------------------------------------------------------------------------

    /**
     * سقف البيانات الخام قبل أي تخصيص: طول stream المعلن قد يكون مضخمًا
     * (ملف غير موثوق) فكان ByteArray(len) يخصص بالطول المعلن قبل فحص أي حد.
     * التخصيص الآن مقيّد بأقل من (الطول المعلن، المتاح فعلاً في الملف، السقف).
     */
    fun rawBytes(s: PdfStream, maxRaw: Int = RAW_STREAM_LIMIT): ByteArray {
        val len = s.length.coerceAtLeast(0)
        if (len > maxRaw) throw PdfFormatException("raw stream too large")
        val dup = s.buf.duplicate()
        dup.position(s.start)
        val read = minOf(len, dup.remaining()).coerceAtLeast(0)
        val out = ByteArray(read)
        if (read > 0) dup.get(out, 0, read)
        return out
    }

    fun decode(s: PdfStream, maxOutput: Int = 64 * 1024 * 1024): ByteArray {
        var data = rawBytes(s)
        val filters = filterNames(s.dict["Filter"] ?: s.dict["F"])
        if (filters.size > budget.maxFilterChain) {
            throw PdfFormatException("filter chain too long (${filters.size} > ${budget.maxFilterChain})")
        }
        val parms = parmList(s.dict["DecodeParms"] ?: s.dict["DP"], filters.size)
        for ((i, f) in filters.withIndex()) {
            data = when (f) {
                "FlateDecode", "Fl" -> applyPredictor(inflate(data, maxOutput), parms[i], maxOutput)
                "ASCIIHexDecode", "AHx" -> asciiHex(data, maxOutput)
                "ASCII85Decode", "A85" -> ascii85(data, maxOutput)
                "LZWDecode", "LZW" -> applyPredictor(lzw(data, int(parms[i]?.get("EarlyChange")) ?: 1, maxOutput), parms[i], maxOutput)
                else -> throw PdfFormatException("unsupported filter $f")
            }
            if (data.size > maxOutput) throw PdfFormatException("stream too large")
        }
        // (ميزانية) فك هذا المُعرَّف (موضعه وطوله) يُحسَب مرة واحدة في ميزانية
        // المستند — فك متكرر لنفس التيار (إعادة طلب صفحة) لا يُنهي مستندات
        // كبيرة سليمة، وفك مداخل كثيرة مختلفة (Do/محتويات) محدود بالسقف.
        budget.chargeDecodedOnce(s, data.size)
        return data
    }

    private fun filterNames(v: Any?): List<String> = when (val r = resolve(v)) {
        is PdfName -> listOf(r.v)
        is List<*> -> r.mapNotNull { name(it) }
        else -> emptyList()
    }

    private fun parmList(v: Any?, n: Int): List<Map<String, Any?>?> {
        val r = resolve(v)
        val out = ArrayList<Map<String, Any?>?>(n)
        for (i in 0 until n) {
            out.add(
                when (r) {
                    is Map<*, *> -> dict(r)
                    is List<*> -> dict(r.getOrNull(i))
                    else -> null
                }
            )
        }
        return out
    }

    private fun inflate(data: ByteArray, max: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        // Overflow-safe initial capacity, never above the output limit.
        val initial = (data.size.toLong() * 3).coerceIn(32L, minOf(max.toLong(), 1L shl 20)).toInt()
        val out = ByteArrayOutputStream(initial)
        val chunk = ByteArray(16 * 1024)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(chunk)
                if (n == 0) break // needs input/dictionary (truncated data): keep what we have
                out.write(chunk, 0, n)
                if (out.size() > max) throw PdfFormatException("stream too large")
            }
        } catch (e: java.util.zip.DataFormatException) {
            // Keep whatever decoded before the damage: partial text beats none.
            if (out.size() == 0) throw PdfFormatException("bad flate data")
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    private fun asciiHex(data: ByteArray, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(data.size / 2 + 8, max).coerceAtLeast(32))
        var hi = -1
        for (b in data) {
            val c = b.toInt() and 0xff
            if (c == '>'.code) break
            val v = PdfLexer.hexVal(c)
            if (v < 0) continue
            if (hi < 0) hi = v else {
                out.write((hi shl 4) or v)
                hi = -1
                // فحص السقف قبل كتابة كل بايت لا بعد اكتمال الناتج كله.
                if (out.size() > max) throw PdfFormatException("stream too large")
            }
        }
        if (hi >= 0) {
            out.write(hi shl 4)
            if (out.size() > max) throw PdfFormatException("stream too large")
        }
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray, max: Int): ByteArray {
        // (ميزانية) السعة الابتدائية مقيدة بسقف الناتج لا بحجم الدخل وحده:
        // تيار خام 128 م.ب كان يطلب ~103 م.ب مقدماً قبل أول فحص.
        val initial = minOf((data.size.toLong() / 5 + 1) * 4, max.toLong() + 64L).toInt().coerceAtLeast(32)
        val out = ByteArrayOutputStream(initial)
        val group = LongArray(5)
        var n = 0
        var i = 0
        fun checkLimit() {
            // فحص السقف عند كل كتابة مجموعة.
            if (out.size() > max) throw PdfFormatException("stream too large")
        }
        if (data.size >= 2 && data[0] == '<'.code.toByte() && data[1] == '~'.code.toByte()) i = 2
        while (i < data.size) {
            val c = data[i++].toInt() and 0xff
            if (PdfLexer.isWs(c)) continue
            if (c == '~'.code) break
            if (c == 'z'.code && n == 0) {
                out.write(0); out.write(0); out.write(0); out.write(0)
                checkLimit()
                continue
            }
            if (c < '!'.code || c > 'u'.code) continue
            group[n++] = (c - '!'.code).toLong()
            if (n == 5) {
                var v = 0L
                for (k in 0 until 5) v = v * 85 + group[k]
                out.write(((v shr 24) and 0xff).toInt()); out.write(((v shr 16) and 0xff).toInt())
                out.write(((v shr 8) and 0xff).toInt()); out.write((v and 0xff).toInt())
                n = 0
                checkLimit()
            }
        }
        if (n > 1) {
            for (k in n until 5) group[k] = 84
            var v = 0L
            for (k in 0 until 5) v = v * 85 + group[k]
            val bytes = intArrayOf(((v shr 24) and 0xff).toInt(), ((v shr 16) and 0xff).toInt(), ((v shr 8) and 0xff).toInt(), (v and 0xff).toInt())
            for (k in 0 until n - 1) out.write(bytes[k])
            checkLimit()
        }
        return out.toByteArray()
    }

    private fun lzw(data: ByteArray, earlyChange: Int, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val table = ArrayList<ByteArray>(4096)
        fun reset() {
            table.clear()
            for (i in 0 until 256) table.add(byteArrayOf(i.toByte()))
            table.add(ByteArray(0)); table.add(ByteArray(0)) // 256 clear, 257 EOD
        }
        reset()
        var bits = 9
        var bitBuf = 0
        var bitCnt = 0
        var prev: ByteArray? = null
        var idx = 0
        while (true) {
            while (bitCnt < bits && idx < data.size) { bitBuf = (bitBuf shl 8) or (data[idx++].toInt() and 0xff); bitCnt += 8 }
            if (bitCnt < bits) break
            val code = (bitBuf shr (bitCnt - bits)) and ((1 shl bits) - 1)
            bitCnt -= bits
            bitBuf = bitBuf and ((1 shl bitCnt) - 1)
            if (code == 256) { reset(); bits = 9; prev = null; continue }
            if (code == 257) break
            val entry: ByteArray = if (code < table.size) table[code]
            else if (prev != null) prev + prev[0]
            else break
            out.write(entry)
            if (out.size() > max) throw PdfFormatException("stream too large")
            if (prev != null && table.size < 4096) table.add(prev + entry[0])
            prev = entry
            val t = table.size + earlyChange
            bits = when { t >= 2048 -> 12; t >= 1024 -> 11; t >= 512 -> 10; else -> 9 }
        }
        return out.toByteArray()
    }

    /** (ميزانية/د1) المرشح التنبؤي. */
    private fun applyPredictor(data: ByteArray, parms: Map<String, Any?>?, maxOutput: Int): ByteArray {
        val predictor = int(parms?.get("Predictor")) ?: 1
        if (predictor < 10) return data
        val columns = int(parms?.get("Columns")) ?: 1
        val colors = int(parms?.get("Colors")) ?: 1
        val bpc = int(parms?.get("BitsPerComponent")) ?: 8
        if (columns <= 0 || colors <= 0 || bpc <= 0) return data
        val bpp = maxOf(1, ((colors.toLong() * bpc + 7) / 8).toInt())
        val rowLenLong = (columns.toLong() * colors * bpc + 7) / 8
        if (rowLenLong > maxOutput) throw PdfFormatException("predictor row too large ($rowLenLong bytes)")
        val rowLen = rowLenLong.toInt()
        if (rowLen <= 0) return data
        val rows = data.size / (rowLen + 1)
        val out = ByteArray(rows * rowLen)
        var prevRow = ByteArray(rowLen)
        var cur = ByteArray(rowLen)
        for (r in 0 until rows) {
            val base = r * (rowLen + 1)
            val ft = data[base].toInt()
            System.arraycopy(data, base + 1, cur, 0, rowLen)
            when (ft) {
                1 -> for (i in bpp until rowLen) cur[i] = (cur[i] + cur[i - bpp]).toByte()
                2 -> for (i in 0 until rowLen) cur[i] = (cur[i] + prevRow[i]).toByte()
                3 -> for (i in 0 until rowLen) {
                    val left = if (i >= bpp) cur[i - bpp].toInt() and 0xff else 0
                    cur[i] = (cur[i] + ((left + (prevRow[i].toInt() and 0xff)) / 2)).toByte()
                }
                4 -> for (i in 0 until rowLen) {
                    val a = if (i >= bpp) cur[i - bpp].toInt() and 0xff else 0
                    val b = prevRow[i].toInt() and 0xff
                    val c = if (i >= bpp) prevRow[i - bpp].toInt() and 0xff else 0
                    val p = a + b - c
                    val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                    val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    cur[i] = (cur[i] + pred).toByte()
                }
            }
            System.arraycopy(cur, 0, out, r * rowLen, rowLen)
            val t = prevRow; prevRow = cur; cur = t
        }
        return out
    }

    private companion object {
        /**
         * سقف البيانات الخام لـ stream واحد قبل أي تخصيص: الملف نفسه
         * محمّل كله في الذاكرة أصلاً، وهذا السقف يمنع طولاً معلناً مضخماً من
         * تخصيص مصفوفة أكبر من الملف كله قبل أي فحص.
         */
        const val RAW_STREAM_LIMIT = 128 shl 20
    }
}
