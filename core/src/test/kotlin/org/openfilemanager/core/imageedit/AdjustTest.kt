package org.openfilemanager.core.imageedit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    @Test
    fun zeroValuesGiveTheIdentityMatrix() {
        assertArrayEquals(identity, Adjust().colorMatrix(), 0.0001f)
        assertTrue(Adjust().isIdentity)
        assertNull(Adjust().matchingPreset)
    }

    @Test
    fun monoTurnsEveryRowIntoLuminance() {
        val m = FilterPreset.MONO.adjust.colorMatrix()
        for (r in 0 until 3) {
            assertEquals(0.299f, m[r * 5], 0.001f)
            assertEquals(0.587f, m[r * 5 + 1], 0.001f)
            assertEquals(0.114f, m[r * 5 + 2], 0.001f)
            assertEquals(0f, m[r * 5 + 3], 0.0001f)
            assertEquals(0f, m[r * 5 + 4], 0.0001f)
        }
        assertEquals(0f, m[15], 0f); assertEquals(0f, m[16], 0f); assertEquals(0f, m[17], 0f)
        assertEquals(1f, m[18], 0f); assertEquals(0f, m[19], 0f)
    }

    @Test
    fun fullBrightnessAddsTheSameOffsetToRgbOnly() {
        val m = Adjust(brightness = 100).colorMatrix()
        val expected = 255f * 0.45f
        assertEquals(expected, m[4], 0.001f)
        assertEquals(expected, m[9], 0.001f)
        assertEquals(expected, m[14], 0.001f)
        assertEquals(0f, m[19], 0f)
    }

    @Test
    fun contrastPivotsAroundMidGrey() {
        val m = Adjust(contrast = 100).colorMatrix()
        // الرمادي 128 يبقى 128: 1.8*128 + (1-1.8)*128
        val grey = 1.8f * 128f + m[4]
        assertEquals(128f, grey, 0.01f)
        assertTrue(m[4] < 0f)
    }

    @Test
    fun warmthBoostsRedAndCutsBlueSymmetrically() {
        val m = Adjust(warmth = 40).colorMatrix()
        assertTrue(m[0] > 1f)
        assertTrue(m[12] < 1f)
        assertEquals(1f, m[6], 0.0001f)
        assertEquals(-m[4], m[14], 0.0001f)
        assertTrue(m[4] > 0f)
    }

    @Test
    fun negativeSaturationIsRejected() {
        var thrown = false
        try { Adjust(saturation = -101) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { Adjust(vignette = 101) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun presetIsDetectedOnlyForExactValues() {
        assertEquals(FilterPreset.VIVID, FilterPreset.VIVID.adjust.matchingPreset)
        assertEquals(FilterPreset.SEPIA, FilterPreset.SEPIA.adjust.matchingPreset)
        assertNull(Adjust(saturation = 34).matchingPreset)
        assertNull(Adjust(brightness = 0, contrast = 12, saturation = 35, warmth = 0, vignette = 10).matchingPreset)
    }

    @Test
    fun matrixCompositionAppliesRightOperandFirst() {
        val scale2 = identity.copyOf().also { it[0] = 2f; it[6] = 2f; it[12] = 2f }
        val shift10 = identity.copyOf().also { it[4] = 10f; it[9] = 10f; it[14] = 10f }
        val composed = Adjust.multiply(scale2, shift10) // 2*(v+10)
        assertEquals(2f, composed[0], 0f)
        assertEquals(20f, composed[4], 0f)
        val other = Adjust.multiply(shift10, scale2) // 2*v+10
        assertEquals(10f, other[4], 0f)
    }

    @Test
    fun everyPresetProducesAFiniteMatrix() {
        for (p in FilterPreset.entries) {
            val m = p.adjust.colorMatrix()
            assertEquals(20, m.size)
            for (v in m) assertFalse("$p has $v", v.isNaN() || v.isInfinite())
        }
        assertFalse(FilterPreset.SEPIA.adjust.isIdentity)
    }
}
