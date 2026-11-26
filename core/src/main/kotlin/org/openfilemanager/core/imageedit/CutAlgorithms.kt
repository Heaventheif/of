package org.openfilemanager.core.imageedit

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * خوارزميات القص الخالصة (بلا أندرويد) كي تُختبر على JVM ويستعملها محرّك القص
 * على الصورة المعاينة وعلى القرار الكامل عند التصدير بالنتيجة نفسها.
 *
 * كل الأقنعة هنا BooleanArray بطول w*h حيث true = بكسل يُزال.
 */
object CutAlgorithms {

    const val MIN_ALPHA = 16

    /** مربّع المسافة اللونية بين لونين ARGB غير مُضروبين بالألفا. */
    fun colorDistance2(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /** أقصى مسافة لونية ≈ 441.7؛ التحويل من نسبة مئوية إلى مسافة إقليدية RGB. */
    fun toleranceDistance(tolerance: Int): Int = (tolerance.coerceIn(0, 100) * 442) / 100

    /** تعبئة منبسطة (flood fill) من بذور معينة. */
    fun flood(
        px: IntArray, w: Int, h: Int,
        seeds: IntArray, seedColor: Int,
        tolerance: Int, edgeStop: Int
    ): BooleanArray {
        val removed = BooleanArray(w * h)
        if (w <= 0 || h <= 0 || seeds.isEmpty()) return removed
        val thr = toleranceDistance(tolerance)
        val thr2 = thr * thr
        val edge = (thr * (1f - 0.85f * edgeStop.coerceIn(0, 100) / 100f)).toInt()
        val edge2 = max(edge, 4) * max(edge, 4)
        val queue = IntArray(w * h)
        var tail = 0
        for (s in seeds) {
            if (s !in px.indices || removed[s]) continue
            if ((px[s] ushr 24) < MIN_ALPHA) continue
            if (colorDistance2(px[s], seedColor) > thr2) continue
            removed[s] = true
            queue[tail++] = s
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) visit(px, removed, queue, i - 1, i, seedColor, thr2, edge2, tail).let { tail = it }
            if (x < w - 1) visit(px, removed, queue, i + 1, i, seedColor, thr2, edge2, tail).let { tail = it }
            if (y > 0) visit(px, removed, queue, i - w, i, seedColor, thr2, edge2, tail).let { tail = it }
            if (y < h - 1) visit(px, removed, queue, i + w, i, seedColor, thr2, edge2, tail).let { tail = it }
        }
        return removed
    }

    private inline fun visit(
        px: IntArray, removed: BooleanArray, queue: IntArray,
        n: Int, from: Int, seedColor: Int, thr2: Int, edge2: Int, tail: Int
    ): Int {
        if (removed[n]) return tail
        val c = px[n]
        if ((c ushr 24) < MIN_ALPHA) return tail
        if (colorDistance2(c, seedColor) > thr2) return tail
        if (colorDistance2(c, px[from]) > edge2) return tail
        removed[n] = true
        queue[tail] = n
        return tail + 1
    }

    /** البكسلات الصالحة على إطار الصورة (صف/عمود واحد سماكاً). */
    fun borderIndices(w: Int, h: Int): IntArray {
        if (w <= 0 || h <= 0) return IntArray(0)
        val out = IntArray(2 * w + 2 * max(0, h - 2))
        var k = 0
        for (x in 0 until w) { out[k++] = x; out[k++] = (h - 1) * w + x }
        for (y in 1 until h - 1) { out[k++] = y * w; out[k++] = y * w + w - 1 }
        return out.copyOf(k)
    }

    /**
     * لون الخلفية المقدَّر: الوسيط لكل قناة من بكسلات الإطار. الوسيط لا تنحرف به
     * بقايا صغيرة عالقة على الحافة (ظل، شعار) كما يحدث مع المتوسط.
     */
    fun estimateBackgroundColor(px: IntArray, w: Int, h: Int): Int? {
        val border = borderIndices(w, h).filter { (px[it] ushr 24) >= MIN_ALPHA }
        if (border.isEmpty()) return null
        fun median(shift: Int): Int {
            val vals = IntArray(border.size) { (px[border[it]] shr shift) and 0xFF }
            vals.sort()
            return vals[vals.size / 2]
        }
        return (0xFF shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
    }

    /**
     * إزالة الخلفية تلقائياً: يُقدَّر لون الإطار ثم تُملأ كل ما يتصل به من بكسلات
     * الإطار ويشبهه. يعاد قناع فارغ إن لم يوجد إطار صالح.
     */
    fun autoBackground(
        px: IntArray, w: Int, h: Int, tolerance: Int, edgeStop: Int
    ): BooleanArray {
        val empty = BooleanArray(w * h)
        val bg = estimateBackgroundColor(px, w, h) ?: return empty
        return flood(px, w, h, borderIndices(w, h), bg, tolerance, edgeStop)
    }

    // ------------------------------------------------------------------
    // تنعيم القناع

    /**
     * يحوّل قناعاً ثنائياً إلى ألفا إزالة 0..255 بحواف ناعمة: يُقلَّص الجانب
     * المُزال نصف القطر أولاً (كي لا تُأكل أطراف الموضوع) ثم يُموّه بمربّع مرتين.
     * [radius] بوحدات بكسل القناع؛ 0 = حافة حادة كما هي.
     */
    fun soften(mask: BooleanArray, w: Int, h: Int, radius: Int): ByteArray {
        val out = ByteArray(w * h)
        if (radius <= 0) {
            for (i in mask.indices) out[i] = if (mask[i]) 0xFF.toByte() else 0
            return out
        }
        val erode = radius / 2
        var src = FloatArray(w * h) { if (mask[it]) 1f else 0f }
        if (erode > 0) src = erodeFull(src, w, h, erode)
        val tmp = FloatArray(w * h)
        val tmp2 = FloatArray(w * h)
        boxBlur(src, tmp, w, h, radius, horizontal = true)
        boxBlur(tmp, tmp2, w, h, radius, horizontal = false)
        val half = max(1, radius / 2)
        boxBlur(tmp2, tmp, w, h, half, horizontal = true)
        boxBlur(tmp, tmp2, w, h, half, horizontal = false)
        for (i in out.indices) out[i] = (tmp2[i] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        return out
    }

    /** يبقي 1 فقط حيث كل نافذة (2r+1)² مملوءة بالكامل (تآكل بالكامل). */
    private fun erodeFull(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        // أفقياً: طول المقاطع الممتلئة
        for (y in 0 until h) {
            var run = 0
            for (x in 0 until w + r) {
                val filled = x < w && src[y * w + x] > 0.5f
                run = if (filled) run + 1 else 0
                val cx = x - r
                if (cx >= 0 && cx < w && run >= 2 * r + 1) tmp[y * w + cx] = 1f
            }
        }
        // رأسياً
        for (x in 0 until w) {
            var run = 0
            for (y in 0 until h + r) {
                val filled = y < h && tmp[y * w + x] > 0.5f
                run = if (filled) run + 1 else 0
                val cy = y - r
                if (cy >= 0 && cy < h && run >= 2 * r + 1) out[cy * w + x] = 1f
            }
        }
        return out
    }

    /** تمويه مربّع أحادي البعد بمجموع متحرك؛ ما خارج الحدود يُعامَل كتكرار لآخر بكسل. */
    private fun boxBlur(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var acc = 0f
            // النافذة الأولى مبنية بتكرار حافة البداية
            for (k in -r..r) acc += src[base + k.coerceIn(0, len - 1) * stride]
            for (i in 0 until len) {
                dst[base + i * stride] = acc / (2 * r + 1)
                val add = (i + r + 1).coerceAtMost(len - 1)
                val sub = (i - r).coerceAtLeast(0)
                acc += src[base + add * stride] - src[base + sub * stride]
            }
        }
    }

    // ------------------------------------------------------------------
    // المضلعات

    /** يحذف النقاط الأقرب من [minDist] من آخر نقطة محفوظة (تخفيف نقاط اللمس). */
    fun <T> thinOut(points: List<T>, minDist: Float, x: (T) -> Float, y: (T) -> Float): List<T> {
        val out = ArrayList<T>(points.size / 2 + 2)
        var lx = Float.NaN
        var ly = Float.NaN
        for (p in points) {
            val px = x(p); val py = y(p)
            if (lx.isNaN() || hypot(px - lx, py - ly) >= minDist) {
                out.add(p); lx = px; ly = py
            }
        }
        return out
    }

    /** تنعيم مضلّع مغلق بقصّ الزوايا (Chaikin): يستبدل كل زاوية بنقطتين ألطف عليها. */
    fun smoothClosed(points: List<Pair<Float, Float>>, iterations: Int = 2): List<Pair<Float, Float>> {
        var cur = points
        repeat(iterations) {
            if (cur.size < 3) return cur
            val next = ArrayList<Pair<Float, Float>>(cur.size * 2)
            for (i in cur.indices) {
                val p = cur[i]
                val q = cur[(i + 1) % cur.size]
                next.add(Pair(0.75f * p.first + 0.25f * q.first, 0.75f * p.second + 0.25f * q.second))
                next.add(Pair(0.25f * p.first + 0.75f * q.first, 0.25f * p.second + 0.75f * q.second))
            }
            cur = next
        }
        return cur
    }

    /** يرسم المضلّع (إحداثيات بكسل القناع، مركز البكسل n+0.5) في قناع ثنائي بمسح الأسطر مع قائمة أضلاع نشطة، فتبقى الكلفة متناسبة مع عدد التقاطعات لا. */
    fun fillPolygon(
        points: List<Pair<Float, Float>>, w: Int, h: Int, invert: Boolean = false
    ): BooleanArray {
        val mask = BooleanArray(w * h)
        val n = points.size
        if (n < 3 || w <= 0 || h <= 0) return mask
        if (invert) {
            // القصّ الحر (إبقاء الداخل): يُمسح الخارج، فيُملأ القناع ثم يُفرَّغ الداخل
            java.util.Arrays.fill(mask, true)
        }
        val minY = IntArray(n); val maxY = IntArray(n)
        for (i in 0 until n) {
            val a = points[i]; val b = points[(i + 1) % n]
            if (a.second == b.second) { minY[i] = Int.MAX_VALUE; maxY[i] = Int.MIN_VALUE; continue }
            minY[i] = floor(min(a.second, b.second) - 0.5f).coerceIn(0f, (h - 1).toFloat()).toInt()
            maxY[i] = ceil(max(a.second, b.second) - 0.5f).coerceIn(0f, (h - 1).toFloat()).toInt()
        }
        val starting = Array(h) { ArrayList<Int>() }
        for (i in 0 until n) if (minY[i] <= maxY[i]) starting[minY[i]].add(i)
        val active = ArrayList<Int>()
        val xs = FloatArray(n)
        for (row in 0 until h) {
            starting[row].forEach { active.add(it) }
            var k = 0
            while (k < active.size) {
                val e = active[k]
                if (row >= maxY[e]) { active.removeAt(k) } else k++
            }
            val cy = row + 0.5f
            // قاعدة نصف مفتوحة (y > cy): الرأس الواقع على خط المسح يُحسب مرة واحدة فلا يتضاعف
            var m = 0
            for (e in active) {
                val a = points[e]; val b = points[(e + 1) % n]
                if ((a.second > cy) == (b.second > cy)) continue
                xs[m++] = a.first + (cy - a.second) * (b.first - a.first) / (b.second - a.second)
            }
            xs.sort(0, m)
            var j = 0
            while (j + 1 < m) {
                val x0 = ceil(xs[j] - 0.5f).coerceAtLeast(0f).toInt()
                val x1 = ceil(xs[j + 1] - 0.5f).coerceAtMost(w.toFloat()).toInt()
                if (x1 > x0) java.util.Arrays.fill(mask, row * w + x0, row * w + x1, !invert)
                j += 2
            }
        }
        return mask
    }

    /** هل النقطة داخل المضلّع (رمي شعاع)؟ */
    fun pointInPolygon(points: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val xi = points[i].first; val yi = points[i].second
            val xj = points[j].first; val yj = points[j].second
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** نصف القطر بالبكسل لمقدار تنعيم 0..100: حتى 2% من ضلع القناع. */
    fun featherRadius(feather: Int, maskMaxSide: Int): Int =
        ((feather.coerceIn(0, 100) / 100f) * 0.02f * maskMaxSide + 0.5f).toInt()

    /** أقصى مسافة بين نقطتين متتاليتين — للتأكد أن التخفيف لم يشوّه المسار. */
    fun maxStep(points: List<Pair<Float, Float>>): Float {
        var m = 0f
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            m = max(m, hypot(b.first - a.first, b.second - a.second))
        }
        return m
    }
}
