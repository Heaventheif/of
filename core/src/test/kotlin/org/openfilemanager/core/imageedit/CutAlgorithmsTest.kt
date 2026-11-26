package org.openfilemanager.core.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class CutAlgorithmsTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun count(m: BooleanArray) = m.count { it }

    @Test
    fun floodStaysInsideTheSimilarRegion() {
        val w = 10
        val h = 10
        val px = IntArray(w * h) { i -> if (i % w < 5) rgb(255, 0, 0) else rgb(0, 0, 255) }
        val mask = CutAlgorithms.flood(px, w, h, intArrayOf(1 * w + 1), rgb(255, 0, 0), 20, 0)
        assertEquals(50, count(mask))
        for (y in 0 until h) for (x in 0 until w) assertEquals("($x,$y)", x < 5, mask[y * w + x])
    }

    @Test
    fun floodDoesNotJumpTransparentPixels() {
        val w = 5
        val px = IntArray(w) { rgb(200, 200, 200) }
        px[2] = 0
        val mask = CutAlgorithms.flood(px, w, 1, intArrayOf(0), rgb(200, 200, 200), 30, 0)
        assertTrue(mask[0] && mask[1])
        assertFalse(mask[2])
        assertFalse(mask[3])
    }

    @Test
    fun edgeStopKeepsTheDarkerObjectInsideALightBackground() {
        val w = 30
        val h = 30
        // خلفية بيضاء ومربع رمادي داكن في الوسط: الفرق اللوني بينهما ≈ 234
        val px = IntArray(w * h) { i -> if ((i % w) in 10..19 && (i / w) in 10..19) rgb(120, 120, 120) else rgb(255, 255, 255) }
        val seeds = CutAlgorithms.borderIndices(w, h)
        val seedColor = rgb(255, 255, 255)
        // حساسية 40 = مسافة 176: لا يصل إلى المربع أصلاً
        val loose = CutAlgorithms.flood(px, w, h, seeds, seedColor, 40, 0)
        assertEquals(100, w * h - count(loose))
        // حساسية 60 = مسافة 265 تبتلع المربع بلا حاجز الحافة، ويبقى محجوباً معه
        val bleeding = CutAlgorithms.flood(px, w, h, seeds, seedColor, 60, 0)
        val guarded = CutAlgorithms.flood(px, w, h, seeds, seedColor, 60, 100)
        assertEquals(0, w * h - count(bleeding))
        assertEquals(100, w * h - count(guarded))
    }

    @Test
    fun autoBackgroundRemovesTheFrameColorOnly() {
        val w = 20
        val h = 20
        val px = IntArray(w * h) { i -> if ((i % w) in 6..13 && (i / w) in 6..13) rgb(255, 0, 0) else rgb(240, 240, 240) }
        val mask = CutAlgorithms.autoBackground(px, w, h, 15, 40)
        assertEquals(w * h - 64, count(mask))
        assertFalse(mask[10 * w + 10])
        assertTrue(mask[0])
    }

    @Test
    fun borderMedianIgnoresASmallStrayMark() {
        val w = 40
        val h = 40
        val px = IntArray(w * h) { rgb(10, 200, 30) }
        for (x in 10..14) { px[x] = rgb(250, 250, 250); px[(h - 1) * w + x] = rgb(250, 250, 250) }
        val bg = CutAlgorithms.estimateBackgroundColor(px, w, h)!!
        assertEquals(rgb(10, 200, 30), bg)
    }

    @Test
    fun softenWithZeroRadiusKeepsTheMaskBinary() {
        val mask = CutAlgorithms.fillPolygon(listOf(0.5f to 0.5f, 20.5f to 0.5f, 20.5f to 20.5f, 0.5f to 20.5f), 32, 32)
        val alpha = CutAlgorithms.soften(mask, 32, 32, 0)
        assertEquals(400, alpha.count { (it.toInt() and 0xFF) == 255 })
        assertEquals(1024 - 400, alpha.count { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun softenProducesIntermediateValuesAndProtectsTheKeptSide() {
        val w = 64
        val h = 64
        val mask = CutAlgorithms.fillPolygon(listOf(0.5f to 0.5f, 40.5f to 0.5f, 40.5f to 63.5f, 0.5f to 63.5f), w, h)
        val alpha = CutAlgorithms.soften(mask, w, h, 4)
        assertTrue(alpha.all { (it.toInt() and 0xFF) in 0..255 })
        assertEquals(255, alpha[32 * w + 20].toInt() and 0xFF)   // عمق المنطقة المُزالة
        assertEquals(0, alpha[32 * w + 60].toInt() and 0xFF)     // عمق المنطقة المحفوظة
        // المنطقة الأكثر إزالة من 50% أصغر من القناع الأصلي: الحافة انحازت للمحذوف
        assertTrue(alpha.count { (it.toInt() and 0xFF) > 127 } < count(mask))
        assertTrue(alpha.count { (it.toInt() and 0xFF) in 1..254 } > 0)
        // انتقال أحادي عبر الحافة
        var prev = 255
        for (x in 30 until 60) {
            val v = alpha[32 * w + x].toInt() and 0xFF
            assertTrue("x=$x v=$v prev=$prev", v <= prev)
            prev = v
        }
    }

    @Test
    fun fillPolygonRectangleAreaIsExact() {
        val mask = CutAlgorithms.fillPolygon(listOf(10.5f to 10.5f, 20.5f to 10.5f, 20.5f to 20.5f, 10.5f to 20.5f), 40, 40)
        assertEquals(100, count(mask))
        assertTrue(mask[15 * 40 + 15])
        assertFalse(mask[9 * 40 + 15])
        assertFalse(mask[20 * 40 + 15])
    }

    @Test
    fun fillPolygonEllipseAreaMatchesPiR1R2() {
        val w = 200
        val h = 100
        val cx = 100f; val cy = 50f; val rx = 80f; val ry = 40f
        val pts = (0 until 180).map { i ->
            val a = 2 * PI * i / 180
            (cx + rx * kotlin.math.cos(a).toFloat()) to (cy + ry * kotlin.math.sin(a).toFloat())
        }
        val area = count(CutAlgorithms.fillPolygon(pts, w, h))
        val expected = (PI * rx * ry).toFloat()
        assertTrue("$area vs $expected", kotlin.math.abs(area - expected) / expected < 0.02f)
    }

    @Test
    fun fillPolygonTreatsNestedRingsAsHoles() {
        // حلقتان متداخلتان تصل بينهما جسر ذهاباً وإياباً على الخط نفسه (يلغي أثره)
        val a = 2.5f to 2.5f; val b = 22.5f to 2.5f; val c = 22.5f to 22.5f; val d = 2.5f to 22.5f
        val e = 9.5f to 9.5f; val f = 15.5f to 9.5f; val g = 15.5f to 15.5f; val h = 9.5f to 15.5f
        val ring = listOf(a, b, c, d, a, e, f, g, h, e)
        val mask = CutAlgorithms.fillPolygon(ring, 24, 24)
        assertEquals(20 * 20 - 6 * 6, count(mask))
        assertFalse(mask[11 * 24 + 11])
        assertTrue(mask[4 * 24 + 4])
    }

    @Test
    fun smoothingSoftensCornersWithoutDriftingFar() {
        val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        val smooth = CutAlgorithms.smoothClosed(square, 2)
        assertEquals(16, smooth.size)
        // كل ضلع صار أربعة أقسام متساوية، والزوايا قُصَّت داخل المربع الأصلي
        assertEquals(25f, CutAlgorithms.maxStep(smooth), 0.01f)
        assertTrue(smooth.all { it.first in 0f..100f && it.second in 0f..100f })
        assertTrue(smooth.none { (x, y) -> (x == 0f || x == 100f) && (y == 0f || y == 100f) })
        assertFalse(CutAlgorithms.pointInPolygon(smooth, 1f, 1f))
        assertTrue(CutAlgorithms.pointInPolygon(smooth, 50f, 50f))
    }

    @Test
    fun thinOutKeepsOnlyPointsFarEnough() {
        val pts = listOf(0f to 0f, 0.5f to 0f, 1f to 0f, 3f to 0f, 3.5f to 0f, 6f to 0f)
        val out = CutAlgorithms.thinOut(pts, 2f, { it.first }, { it.second })
        assertEquals(listOf(0f to 0f, 3f to 0f, 6f to 0f), out)
    }

    @Test
    fun featherRadiusGrowsWithFeatherAndNeverNegative() {
        assertEquals(0, CutAlgorithms.featherRadius(0, 2048))
        assertEquals(0, CutAlgorithms.featherRadius(-5, 2048))
        assertEquals(41, CutAlgorithms.featherRadius(100, 2048))
        assertTrue(CutAlgorithms.featherRadius(50, 2048) > CutAlgorithms.featherRadius(25, 2048))
    }

    @Test
    fun shapePolygonsCoverTheDraggedBox() {
        val rect = CutShapes.polygon(CutShape.RECT, 60f, 40f, 10f, 10f)
        assertEquals(
            listOf(10f to 10f, 60f to 10f, 60f to 40f, 10f to 40f), rect
        )
        val circle = CutShapes.polygon(CutShape.CIRCLE, 50f, 50f, 50f, 80f)
        assertEquals(96, circle.size)
        for ((x, y) in circle) assertEquals(30f, kotlin.math.hypot(x - 50f, y - 50f), 0.01f)
        val square = CutShapes.polygon(CutShape.SQUARE, 0f, 0f, 30f, 10f)
        val sw = square.maxOf { it.first } - square.minOf { it.first }
        val sh = square.maxOf { it.second } - square.minOf { it.second }
        assertEquals(sw, sh, 0.001f)
    }

    @Test
    fun distanceAndToleranceAreMonotonic() {
        assertEquals(0, CutAlgorithms.colorDistance2(rgb(3, 4, 5), rgb(3, 4, 5)))
        assertEquals(27, CutAlgorithms.colorDistance2(rgb(0, 0, 0), rgb(3, 3, 3)))
        assertEquals(0, CutAlgorithms.toleranceDistance(0))
        assertEquals(442, CutAlgorithms.toleranceDistance(100))
        assertTrue(CutAlgorithms.toleranceDistance(60) > CutAlgorithms.toleranceDistance(30))
        // أبعد لونين يقعان داخل أقصى حساسية
        assertTrue(CutAlgorithms.colorDistance2(rgb(255, 255, 255), 0) <= 442 * 442)
        assertTrue(sqrt(CutAlgorithms.colorDistance2(rgb(255, 255, 255), 0).toDouble()) < 442.0)
    }
}
