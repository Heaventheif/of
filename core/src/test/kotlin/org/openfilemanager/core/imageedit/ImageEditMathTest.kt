package org.openfilemanager.core.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * اختبارات JVM لرياضيات المحرّر (القصّ، التحويل، التاريخ). تعمل بلا أندرويد
 * وتدخل في أمر CI الحالي: ./gradlew :core:test
 */
class ImageEditMathTest {

    // ---------- Affine2 ----------

    @Test
    fun identityKeepsPoints() {
        val p = Affine2.IDENTITY.apply(3f, -7f)
        assertEquals(3f, p.first, 1e-4f)
        assertEquals(-7f, p.second, 1e-4f)
    }

    @Test
    fun rotation90MapsAxesClockwise() {
        val r = Affine2.rotationDegrees(90f)
        val e1 = r.apply(1f, 0f)   // (1,0) -> (0,1): محور x إلى محور y (y لأسفل = باتجاه الساعة)
        assertEquals(0f, e1.first, 1e-4f)
        assertEquals(1f, e1.second, 1e-4f)
        val e2 = r.apply(0f, 1f)   // (0,1) -> (-1,0)
        assertEquals(-1f, e2.first, 1e-4f)
        assertEquals(0f, e2.second, 1e-4f)
    }

    @Test
    fun compositionThenTranslationMatchesManualOrder() {
        // طبّق translate(10,20) أولاً ثم scale(2,2): (1,1) -> (11,21) -> (22,42)
        val m = Affine2.translation(10f, 20f).then(Affine2.scaling(2f, 2f))
        val p = m.apply(1f, 1f)
        assertEquals(22f, p.first, 1e-4f)
        assertEquals(42f, p.second, 1e-4f)
    }

    @Test
    fun inverseUndoesTransform() {
        val m = Affine2.rotationDegrees(30f).then(Affine2.translation(-5f, 8f))
        val inv = m.inverted()
        assertNotNull(inv)
        val p = m.apply(13f, 29f)
        val back = inv!!.apply(p.first, p.second)
        assertEquals(13f, back.first, 1e-3f)
        assertEquals(29f, back.second, 1e-3f)
    }

    @Test
    fun mirrorMapsWidthCorrectly() {
        val m = Affine2.horizontalMirror(100f)
        val p = m.apply(20f, 40f)
        assertEquals(80f, p.first, 1e-4f)
        assertEquals(40f, p.second, 1e-4f)
    }

    // ---------- CropMath ----------

    @Test
    fun aspectFitCentersAndKeepsRatio() {
        val r = CropMath.fitAspect(FloatRect.xywh(0f, 0f, 100f, 100f), 1f)
        assertEquals(0f, r.left, 1e-3f)
        assertEquals(0f, r.top, 1e-3f)
        assertEquals(100f, r.width, 1e-3f)
        assertEquals(100f, r.height, 1e-3f)

        val r2 = CropMath.fitAspect(FloatRect.xywh(0f, 0f, 100f, 50f), 1f) // أوسع من اللازم
        assertEquals(50f, r2.width, 1e-3f)
        assertEquals(50f, r2.height, 1e-3f)
        assertEquals(25f, r2.left, 1e-3f)
    }

    @Test
    fun centeredAspectCrop16to9() {
        val c = CropMath.centeredAspectCrop(1920f, 1080f, 16f / 9f)
        assertNotNull(c)
        assertEquals(1920f, c!!.width, 1e-3f)
        assertEquals(1080f, c.height, 1e-3f)

        val c2 = CropMath.centeredAspectCrop(1000f, 1000f, 16f / 9f)
        assertNotNull(c2)
        assertEquals(1000f, c2!!.width, 1e-3f)
        assertEquals(562.5f, c2.height, 1e-2f)
    }

    @Test
    fun rotateQuarterRoundTrip() {
        val r = FloatRect.xywh(10f, 20f, 30f, 40f)
        var cur = r
        cur = CropMath.rotateQuarterCw(cur, 100f, 90f)
        cur = CropMath.rotateQuarterCw(cur, 90f, 100f)
        cur = CropMath.rotateQuarterCw(cur, 100f, 90f)
        cur = CropMath.rotateQuarterCw(cur, 90f, 100f)
        assertEquals(r.left, cur.left, 1e-4f)
        assertEquals(r.top, cur.top, 1e-4f)
        assertEquals(r.right, cur.right, 1e-4f)
        assertEquals(r.bottom, cur.bottom, 1e-4f)
    }

    @Test
    fun rotateQuarterCwMapsCorners() {
        // لوحة 100×90، دورة ساعة: (0,0) -> (90,0) والزاوية (100,90) -> (0,100)
        val m = Affine2.quarterTurnCw(100f, 90f)
        val tl = m.apply(0f, 0f)
        assertEquals(90f, tl.first, 1e-4f)
        assertEquals(0f, tl.second, 1e-4f)
        val br = m.apply(100f, 90f)
        assertEquals(0f, br.first, 1e-4f)
        assertEquals(100f, br.second, 1e-4f)
    }

    @Test
    fun clampedKeepingSizeShiftsInside() {
        val r = FloatRect.xywh(-20f, 10f, 50f, 30f) // يخرج يساراً
        val c = r.clampedKeepingSize(100f, 100f)
        assertEquals(0f, c.left, 1e-4f)
        assertEquals(50f, c.width, 1e-4f)
        assertEquals(30f, c.height, 1e-4f)
    }

    @Test
    fun rotatedBoundsForCardinalAngles() {
        assertEquals(100f, CropMath.rotatedBounds(100f, 50f, 0f).first, 1e-3f)
        assertEquals(50f, CropMath.rotatedBounds(100f, 50f, 90f).first, 1e-3f)
        assertEquals(100f, CropMath.rotatedBounds(100f, 50f, 90f).second, 1e-3f)
        // 45°: (100+50)/√2 ≈ 106.07
        assertEquals(106.066f, CropMath.rotatedBounds(100f, 50f, 45f).first, 0.01f)
    }

    // ---------- ImageTransform ----------

    @Test
    fun defaultTransformIsIdentity() {
        val t = ImageTransform()
        assertEquals(100f, t.preCropSize(100f, 50f).first, 1e-4f)
        assertEquals(50f, t.preCropSize(100f, 50f).second, 1e-4f)
        val p = t.fullMatrix(100f, 50f).apply(25f, 10f)
        assertEquals(25f, p.first, 1e-4f)
        assertEquals(10f, p.second, 1e-4f)
    }

    @Test
    fun quarterTurnSwapsSizeAndMapsCenter() {
        val t = ImageTransform(quarterTurns = 1)
        val (w, h) = t.preCropSize(100f, 200f)
        assertEquals(200f, w, 1e-4f)
        assertEquals(100f, h, 1e-4f)
        // مركز الصورة يبقى مركز اللوحة
        val c = t.fullMatrix(100f, 200f).apply(50f, 100f)
        assertEquals(100f, c.first, 1e-3f)
        assertEquals(50f, c.second, 1e-3f)
    }

    @Test
    fun cropOffsetAppliedInMatrix() {
        // doc 100×200، دورة ربع: لوحة 200×100، قصّ (50,25)-(150,75)
        val t = ImageTransform(quarterTurns = 1, crop = FloatRect.xywh(50f, 25f, 100f, 50f))
        assertEquals(100f, t.finalSize(100f, 200f).first, 1e-4f)
        assertEquals(50f, t.finalSize(100f, 200f).second, 1e-4f)
        // مركز الصورة (50,100) -> مركز المخرَج (50,25)
        val c = t.fullMatrix(100f, 200f).apply(50f, 100f)
        assertEquals(50f, c.first, 1e-3f)
        assertEquals(25f, c.second, 1e-3f)
    }

    @Test
    fun freeRotationGrowsCanvas() {
        val t = ImageTransform(freeRotation = 45f)
        val (w, h) = t.preCropSize(100f, 100f)
        assertEquals(141.42f, w, 0.05f)
        assertEquals(141.42f, h, 0.05f)
        // المركز يبقى في المركز
        val c = t.fullMatrix(100f, 100f).apply(50f, 50f)
        assertEquals(70.71f, c.first, 0.05f)
        assertEquals(70.71f, c.second, 0.05f)
    }

    @Test
    fun bothFlipsNormalizeToHalfTurn() {
        val t = ImageTransform(flipH = true, flipV = true).normalized()
        assertFalse(t.flipH)
        assertFalse(t.flipV)
        assertEquals(2, t.quarterTurns)
        // (x,y) -> (w-x, h-y) كما هو متوقع
        val p = t.fullMatrix(100f, 50f).apply(25f, 10f)
        assertEquals(75f, p.first, 1e-4f)
        assertEquals(40f, p.second, 1e-4f)
    }

    @Test
    fun appendQuarterTurnFourTimesReturnsOriginal() {
        var t = ImageTransform(crop = FloatRect.xywh(10f, 20f, 60f, 30f)).normalized()
        val before = t
        repeat(4) { t = t.appendedQuarterTurn(100f, 50f, 1) }
        assertEquals(before.quarterTurns, t.quarterTurns)
        assertEquals(before.flipH, t.flipH)
        assertEquals(before.flipV, t.flipV)
        assertEquals(before.freeRotation, t.freeRotation, 1e-4f)
        // القصّ يُعاد تعيينه إلى موضعه الأصلي بعد أربع دورات
        assertEquals(before.crop!!.left, t.crop!!.left, 1e-3f)
        assertEquals(before.crop!!.top, t.crop!!.top, 1e-3f)
        assertEquals(before.crop!!.right, t.crop!!.right, 1e-3f)
        assertEquals(before.crop!!.bottom, t.crop!!.bottom, 1e-3f)
    }

    @Test
    fun appendQuarterTurnMapsContentConsistently() {
        // نقطة في الصورة تظل تُعرض (بعد الإلحاق) حيث تتوقعها العين:
        // نتحقق أن مصفوفة الحالة الجديدة = دورة في نهاية سلسلة الحالة القديمة.
        val old = ImageTransform(quarterTurns = 1, freeRotation = 10f, crop = FloatRect.xywh(20f, 10f, 80f, 40f))
        val (pw, ph) = old.preCropSize(100f, 50f)
        val appended = old.appendedQuarterTurn(100f, 50f, 1)
        val mOld = old.preCropMatrix(100f, 50f)
        val mNew = appended.preCropMatrix(100f, 50f)
        val op = Affine2.quarterTurnCw(pw, ph)
        for (p in listOf(Pair(0f, 0f), Pair(100f, 0f), Pair(30f, 45f), Pair(100f, 50f), Pair(12f, 34f))) {
            val viaOld = mOld.apply(p.first, p.second)
            val expected = op.apply(viaOld.first, viaOld.second)
            val viaNew = mNew.apply(p.first, p.second)
            assertEquals(expected.first, viaNew.first, 1e-2f)
            assertEquals(expected.second, viaNew.second, 1e-2f)
        }
    }

    @Test
    fun appendFlipTwiceReturnsOriginal() {
        var t = ImageTransform(quarterTurns = 2, freeRotation = 20f, crop = FloatRect.xywh(5f, 8f, 40f, 30f))
        val before = t.normalized()
        t = t.appendedFlip(100f, 50f, horizontal = true).appendedFlip(100f, 50f, horizontal = true)
        assertEquals(before.quarterTurns, t.quarterTurns)
        assertEquals(before.flipH, t.flipH)
        assertEquals(before.freeRotation, t.freeRotation, 1e-4f)
        assertEquals(before.crop!!.left, t.crop!!.left, 1e-3f)
    }

    @Test
    fun appendFlipMapsContentConsistently() {
        for (horizontal in listOf(true, false)) {
            val old = ImageTransform(quarterTurns = 1, freeRotation = 15f, crop = FloatRect.xywh(10f, 5f, 60f, 40f))
            val (pw, ph) = old.preCropSize(100f, 50f)
            val appended = old.appendedFlip(100f, 50f, horizontal)
            val mOld = old.preCropMatrix(100f, 50f)
            val mNew = appended.preCropMatrix(100f, 50f)
            val op = if (horizontal) Affine2.horizontalMirror(pw) else Affine2.verticalMirror(ph)
            for (p in listOf(Pair(0f, 0f), Pair(100f, 50f), Pair(37f, 11f), Pair(90f, 25f))) {
                val viaOld = mOld.apply(p.first, p.second)
                val expected = op.apply(viaOld.first, viaOld.second)
                val viaNew = mNew.apply(p.first, p.second)
                assertEquals(expected.first, viaNew.first, 1e-2f)
                assertEquals(expected.second, viaNew.second, 1e-2f)
            }
        }
    }

    @Test
    fun cropStaysInsidePreCropCanvasAfterRotation() {
        var t = ImageTransform(crop = FloatRect.xywh(10f, 20f, 60f, 25f))
        t = t.appendedQuarterTurn(100f, 50f, 1)
        val (pw, ph) = t.preCropSize(100f, 50f)
        val c = t.crop
        assertNotNull(c)
        assertTrue(c!!.left >= -1f && c.top >= -1f && c.right <= pw + 1f && c.bottom <= ph + 1f)
    }

    @Test
    fun normalizeFoldsLargeFreeRotationIntoTurns() {
        val t = ImageTransform(quarterTurns = 0, freeRotation = 100f).normalized()
        assertEquals(1, t.quarterTurns)
        assertEquals(10f, t.freeRotation, 1e-4f)
    }

    @Test
    fun withFreeRotationClampsToRange() {
        val t = ImageTransform(quarterTurns = 2).withFreeRotation(80f)
        assertEquals(45f, t.freeRotation, 1e-4f)
        assertEquals(2, t.quarterTurns)
    }

    @Test
    fun totalRotationDegreesMatchesFields() {
        val t = ImageTransform(quarterTurns = 3, freeRotation = 20f)
        assertEquals(290f, t.totalRotationDegrees, 1e-4f)
    }

    // ---------- EditHistory ----------

    @Test
    fun historyUndoRedoCycle() {
        val h = EditHistory<String>()
        var state = "a"
        h.record(state); state = "b"
        h.record(state); state = "c"
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
        state = h.undo(state)!!
        assertEquals("b", state)
        state = h.undo(state)!!
        assertEquals("a", state)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        state = h.redo(state)!!
        assertEquals("b", state)
        assertTrue(h.canUndo)
        assertTrue(h.canRedo)
    }

    @Test
    fun historyRecordClearsRedo() {
        val h = EditHistory<Int>()
        var s = 1
        h.record(s); s = 2
        val back = h.undo(s)!!
        assertEquals(1, back)
        assertTrue(h.canRedo)
        h.record(back); // تعديل جديد يمسح مستقبل الإعادة
        assertFalse(h.canRedo)
    }

    @Test
    fun historyCapacityDropsOldest() {
        val h = EditHistory<Int>(capacity = 3)
        var s = 0
        repeat(5) { h.record(s); s++ }
        assertEquals(3, h.pastSizeForTest())
        // أقدم حالتين سقطتا: أول تراجع يعطي 4
        assertEquals(4, h.undo(s))
    }

    @Test
    fun historyClear() {
        val h = EditHistory<Int>()
        h.record(1)
        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo(2))
        assertNull(h.redo(2))
    }

    @Test
    fun rotatedBoundsSymmetry() {
        val (w1, h1) = CropMath.rotatedBounds(300f, 400f, 30f)
        val (w2, h2) = CropMath.rotatedBounds(300f, 400f, -30f)
        assertEquals(w1, w2, 1e-3f)
        assertEquals(h1, h2, 1e-3f)
        // w = 300·cos30 + 400·sin30 ≈ 459.81 ، h = 300·sin30 + 400·cos30 ≈ 496.41
        assertEquals(459.81f, w1, 0.02f)
        assertEquals(496.41f, h1, 0.02f)
    }
}
