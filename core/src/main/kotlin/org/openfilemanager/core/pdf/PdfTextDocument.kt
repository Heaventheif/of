package org.openfilemanager.core.pdf

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** One table-of-contents row. [pageIndex] is 0-based, or -1 when the destination could not be resolved. */
data class PdfOutlineEntry(val title: String, val level: Int, val pageIndex: Int)

/** Where a run of text starts, in PDF user space (origin bottom-left, y up) of the unrotated page. */
data class PdfTextRun(val start: Int, val x: Double, val y: Double, val size: Double)

/**
 * Extracted text of one page. [box] = visible page box [x0, y0, x1, y1] in user space; [rotate] = /Rotate.
 * [usableFonts] is false when the page shows text through fonts we cannot map to Unicode.
 */
class PdfPageText(val text: String, val runs: List<PdfTextRun>, val box: DoubleArray, val rotate: Int, val skippedUnmapped: Boolean) {
    /**
     * Approximate horizontal band of the text line containing character [offset], as fractions of the page
     * height measured from the top: [top, height]. Null when the page is rotated or the offset has no run.
     * (Glyph-level boxes are not available: widths of individual matches are unknown.)
     */
    /**
     * نطاق السطر [start, end) في [text] الأقرب إلى ارتفاع [yFrac] (نسبة من أعلى الصفحة)،
     * أو null للصفحات المدوّرة/بلا نص. للتمييز المرئي في نافذة تحديد النص.
     */
    fun lineRange(yFrac: Double): IntArray? {
        if (rotate != 0 || runs.isEmpty()) return null
        val h = box[3] - box[1]
        if (h <= 0.0) return null
        val ty = box[3] - yFrac * h
        var best = runs[0]
        var bd = Math.abs(best.y + best.size * 0.3 - ty)
        for (r in runs) {
            val d = Math.abs(r.y + r.size * 0.3 - ty)
            if (d < bd) { best = r; bd = d }
        }
        val tol = Math.max(1.0, best.size * 0.5)
        var first = -1
        var last = -1
        for ((i, r) in runs.withIndex()) {
            if (Math.abs(r.y - best.y) <= tol) { if (first < 0) first = i; last = i }
        }
        if (first < 0) return null
        val start = runs[first].start.coerceIn(0, text.length)
        val nl = text.indexOf('\n', runs[last].start.coerceIn(0, text.length))
        return intArrayOf(start, if (nl < 0) text.length else nl)
    }

    fun lineBand(offset: Int): DoubleArray? {
        if (rotate != 0 || runs.isEmpty()) return null
        var lo = 0
        var hi = runs.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid].start <= offset) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        val r = runs[found]
        val h = box[3] - box[1]
        if (h <= 0.0) return null
        val top = (box[3] - (r.y + r.size * 0.95)) / h
        val height = r.size * 1.25 / h
        return doubleArrayOf(top.coerceIn(0.0, 1.0), height.coerceIn(0.0, 1.0))
    }
}

/**
 * Read-only access to a PDF's outline and page text, without any external library.
 * Rendering stays with android.graphics.pdf.PdfRenderer; this class only supplies what that API lacks
 * on the app's minSdk (outline + text for search). Not thread-safe by itself: every public method is synchronized.
 */
class PdfTextDocument private constructor(
    private val file: PdfFile,
    private val raf: RandomAccessFile?
) : Closeable {

    private class PageNode(
        val ref: Int,
        val dict: Map<String, Any?>,
        val resources: Any?,
        val mediaBox: Any?,
        val cropBox: Any?,
        val rotate: Any?
    )

    private val pages: List<PageNode> by lazy { collectPages() }
    private val pageIndexByRef: Map<Int, Int> by lazy { pages.withIndex().associate { it.value.ref to it.index } }
    private val fontCache = HashMap<Int, PdfFont>()
    private val inlineFontCache = HashMap<Map<String, Any?>, PdfFont>()

    @get:Synchronized
    val pageCount: Int get() = pages.size

    @Synchronized
    fun outline(): List<PdfOutlineEntry> {
        val outlines = file.dict(file.root()["Outlines"]) ?: return emptyList()
        val out = ArrayList<PdfOutlineEntry>()
        val seen = HashSet<Int>()
        walkOutline(outlines["First"], 0, out, seen)
        return out
    }

    @Synchronized
    fun pageText(index: Int): PdfPageText {
        val node = pages.getOrNull(index) ?: throw IndexOutOfBoundsException("page $index")
        val interp = PdfContentInterpreter(this)
        val contents = contentBytes(node)
        interp.run(contents, node.resources, 0, doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
        val box = pageBox(node)
        return PdfPageText(interp.text(), interp.runs, box, ((file.int(node.rotate) ?: 0) % 360 + 360) % 360, interp.skippedUnmapped)
    }

    override fun close() {
        runCatching { raf?.close() }
    }

    // ---- pages --------------------------------------------------------------------------------------------

    private fun collectPages(): List<PageNode> {
        val result = ArrayList<PageNode>()
        val seen = HashSet<Int>()
        fun visit(ref: Any?, res: Any?, mb: Any?, cb: Any?, rot: Any?, depth: Int) {
            if (depth > 64 || result.size >= MAX_PAGES) return
            val num = (ref as? PdfRef)?.num
            if (num != null && !seen.add(num)) return
            val d = file.dict(ref) ?: return
            val r = d["Resources"] ?: res
            val m = d["MediaBox"] ?: mb
            val c = d["CropBox"] ?: cb
            val ro = d["Rotate"] ?: rot
            val kids = file.array(d["Kids"])
            if (kids != null && file.name(d["Type"]) != "Page") {
                for (k in kids) visit(k, r, m, c, ro, depth + 1)
            } else {
                result.add(PageNode(num ?: -1 - result.size, d, r, m, c, ro))
            }
        }
        visit(file.root()["Pages"], null, null, null, null, 0)
        return result
    }

    private fun pageBox(n: PageNode): DoubleArray {
        val arr = file.array(n.cropBox) ?: file.array(n.mediaBox)
        val v = arr?.mapNotNull { file.num(it) }
        if (v != null && v.size == 4) {
            return doubleArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
        }
        return doubleArrayOf(0.0, 0.0, 612.0, 792.0)
    }

    private fun contentBytes(n: PageNode): ByteArray {
        val c = file.resolve(n.dict["Contents"])
        val out = java.io.ByteArrayOutputStream()
        // (ميزانية) مجموع محتوى صفحة واحدة محدود: مصفوفة /Contents بعدائية كانت تُجمّع مداخل كل منها 64 م.ب بلا سقف كلي فيسقط الاستخراج بلا ذاكرة — استثناء…
        fun add(v: Any?) {
            val s = file.resolve(v) as? PdfStream ?: return
            try {
                val decoded = file.decode(s)
                if (out.size() + decoded.size > file.budget.maxPageContentBytes) {
                    throw PdfBudgetException("page content too large (${out.size() + decoded.size} bytes)")
                }
                out.write(decoded); out.write('\n'.code)
            } catch (e: PdfBudgetException) {
                throw e
            } catch (_: Exception) {
                // تيار تالف واحد: يُتجاوز ويُكمل البقية — نص جزئي أفضل من لا شيء.
            }
        }
        if (c is List<*>) c.forEach { add(it) } else add(c)
        return out.toByteArray()
    }

    // ---- outline ------------------------------------------------------------------------------------------

    private fun walkOutline(first: Any?, level: Int, out: MutableList<PdfOutlineEntry>, seen: MutableSet<Int>) {
        if (level > 24) return
        var cur = first
        while (cur != null && out.size < MAX_OUTLINE) {
            val num = (cur as? PdfRef)?.num
            if (num != null && !seen.add(num)) return
            val item = file.dict(cur) ?: return
            val title = textString(file.resolve(item["Title"]))?.trim().orEmpty()
            if (title.isNotEmpty()) out.add(PdfOutlineEntry(title, level, destinationPage(item)))
            if (item["First"] != null) walkOutline(item["First"], level + 1, out, seen)
            cur = item["Next"]
        }
    }

    private fun destinationPage(item: Map<String, Any?>): Int {
        var dest = item["Dest"]
        if (dest == null) {
            val action = file.dict(item["A"])
            if (action != null && file.name(action["S"]) == "GoTo") dest = action["D"]
        }
        return pageOfDest(dest, 0)
    }

    private fun pageOfDest(dest: Any?, depth: Int): Int {
        if (depth > 4) return -1
        return when (val d = file.resolve(dest)) {
            is List<*> -> when (val first = d.firstOrNull()) {
                is PdfRef -> pageIndexByRef[first.num] ?: -1
                is Number -> first.toInt().takeIf { it in 0 until pages.size } ?: -1
                else -> -1
            }
            is PdfName -> pageOfDest(namedDests[d.v], depth + 1)
            is PdfString -> pageOfDest(namedDests[String(d.bytes, Charsets.ISO_8859_1)], depth + 1)
            is Map<*, *> -> pageOfDest(d["D"], depth + 1)
            else -> -1
        }
    }

    private val namedDests: Map<String, Any?> by lazy {
        val map = HashMap<String, Any?>()
        val root = file.root()
        file.dict(root["Dests"])?.forEach { (k, v) -> map[k] = v }
        val names = file.dict(file.dict(root["Names"])?.get("Dests"))
        if (names != null) {
            val seen = HashSet<Int>()
            fun walk(node: Map<String, Any?>, depth: Int) {
                if (depth > 16 || map.size > MAX_NAMED) return
                file.array(node["Names"])?.let { arr ->
                    var i = 0
                    while (i + 1 < arr.size) {
                        val k = file.resolve(arr[i]) as? PdfString
                        if (k != null) map[String(k.bytes, Charsets.ISO_8859_1)] = arr[i + 1]
                        i += 2
                    }
                }
                file.array(node["Kids"])?.forEach { kid ->
                    val n = (kid as? PdfRef)?.num
                    if (n == null || seen.add(n)) file.dict(kid)?.let { walk(it, depth + 1) }
                }
            }
            walk(names, 0)
        }
        map
    }

    // ---- fonts (used by the interpreter) -------------------------------------------------------------------

    internal fun fontsOf(resources: Any?): Map<String, Any?> =
        file.dict(file.dict(resources)?.get("Font")) ?: emptyMap()

    internal fun fontFor(entry: Any?): PdfFont {
        val num = (entry as? PdfRef)?.num
        if (num != null) fontCache[num]?.let { return it }
        val d = file.dict(entry) ?: return PdfFont.UNMAPPED
        val f = if (num == null) inlineFontCache.getOrPut(d) { PdfFontLoader(file).load(d) } else PdfFontLoader(file).load(d)
        if (num != null) fontCache[num] = f
        return f
    }

    internal fun xobject(resources: Any?, name: String): PdfStream? =
        file.resolve(file.dict(file.dict(resources)?.get("XObject"))?.get(name)) as? PdfStream

    internal fun decodeStream(s: PdfStream): ByteArray = file.decode(s)
    internal fun dictOf(v: Any?): Map<String, Any?>? = file.dict(v)
    internal fun numbers(v: Any?): List<Double>? = file.array(v)?.mapNotNull { file.num(it) }

    companion object {
        private const val MAX_PAGES = 100_000
        private const val MAX_OUTLINE = 5_000
        private const val MAX_NAMED = 100_000

        /** @throws PdfEncryptedException, PdfFormatException (or IO errors) when the file cannot be read. */
        fun open(file: File): PdfTextDocument = open(file, PdfResourceBudget())

        /** مُخصّص للاختبارات العدائية: ميزانية مصغّرة لإثبات الرفض قبل استهلاك الموارد. */
        internal fun open(file: File, budget: PdfResourceBudget): PdfTextDocument {
            val raf = RandomAccessFile(file, "r")
            try {
                if (raf.length() > Int.MAX_VALUE) throw PdfFormatException("file too large")
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                return build(map, raf, budget)
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw t
            }
        }

        fun open(bytes: ByteArray): PdfTextDocument = open(bytes, PdfResourceBudget())

        /** مُخصّص للاختبارات العدائية (نفس عقد الدالة العامة). */
        internal fun open(bytes: ByteArray, budget: PdfResourceBudget): PdfTextDocument = build(ByteBuffer.wrap(bytes), null, budget)

        private fun build(buf: ByteBuffer, raf: RandomAccessFile?, budget: PdfResourceBudget = PdfResourceBudget()): PdfTextDocument {
            val f = PdfFile(buf, budget)
            if (f.isEncrypted) throw PdfEncryptedException()
            return PdfTextDocument(f, raf)
        }

        /** PDF "text string": UTF-16 with BOM, UTF-8 with BOM, otherwise PDFDocEncoding (≈ Latin-1). */
        internal fun decodeTextString(b: ByteArray): String {
            if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
            if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
            return String(b, Charset.forName("ISO-8859-1"))
        }
    }

    private fun textString(v: Any?): String? = when (v) {
        is PdfString -> decodeTextString(v.bytes)
        is PdfName -> v.v
        else -> null
    }
}
