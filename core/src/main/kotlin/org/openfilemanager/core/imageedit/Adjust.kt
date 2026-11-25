package org.openfilemanager.core.imageedit

import kotlin.math.abs

/**
 * تعديلات الصورة الأساس (الإضاءة/التباين/التشبّع/الدفء/التظليل الحوافي). قيم كل
 * مفردة -100..100 حيث 0 = بلا تغيير. خالصة بلا أندرويد كي تُختبر على JVM، وتتحوّل
 * إلى مصفوفة ألوان 4×5 عند العرض والتصدير — فلا تُعدَّل البكسلات أبداً قبل الحفظ.
 */
data class Adjust(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val warmth: Int = 0,
    /** التظليل الحوافي 0..100 (يُرسم تدرجاً شعاعياً، ليس ضمن مصفوفة الألوان). */
    val vignette: Int = 0
) {
    init {
        require(brightness in -100..100 && contrast in -100..100 && saturation in -100..100) {
            "brightness/contrast/saturation must be within -100..100"
        }
        require(warmth in -100..100 && vignette in 0..100) {
            "warmth must be within -100..100 and vignette within 0..100"
        }
    }

    val isIdentity: Boolean get() = this == Adjust()

    /** اسم الفلتر المطابق تماماً للقيم الحالية، أو null إن كانت قيماً مخصصة. */
    val matchingPreset: FilterPreset? get() = FilterPreset.entries.firstOrNull { it != FilterPreset.NONE && it.adjust == this }

    /**
     * مصفوفة ألوان 4 صفوف × 5 أعمدة (ترتيب android.graphics.ColorMatrix): الصفوف R,G,B,A
     * وكل صف معاملات [R G B A إزاحة]. صف الألفا ثابت (0,0,0,1,0).
     */
    fun colorMatrix(): FloatArray {
        // 1) التشبّع حول إضاءة الصورة
        val sat = 1f + saturation / 100f
        var m = FloatArray(20)
        val lum = floatArrayOf(0.299f, 0.587f, 0.114f)
        for (r in 0 until 3) {
            for (c in 0 until 3) m[r * 5 + c] = (if (r == c) sat else 0f) + (1f - sat) * lum[c]
        }
        // صف الألفا: معامل 1 فقط، وإلا لصارت الصورة شفافة تماماً
        m[18] = 1f
        // 2) الدفء: مكسب للأحمر وخسارة للأزرق مع إزاحة تُبقي منتصفات الألوان متوازنة
        val w = warmth / 100f * 0.35f
        val warmOffset = 255f * w * 0.15f
        m = multiply(
            floatArrayOf(1f + w, 0f, 0f, 0f, warmOffset, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - w, 0f, -warmOffset, 0f, 0f, 0f, 1f, 0f),
            m
        )
        // 3) التباين حول منتصف المدى
        val con = 1f + contrast / 100f * 0.8f
        val conOffset = (1f - con) * 128f
        m = multiply(
            floatArrayOf(con, 0f, 0f, 0f, conOffset, 0f, con, 0f, 0f, conOffset, 0f, 0f, con, 0f, conOffset, 0f, 0f, 0f, 1f, 0f),
            m
        )
        // 4) الإضاءة
        val brightOffset = brightness / 100f * 255f * 0.45f
        m = multiply(
            floatArrayOf(1f, 0f, 0f, 0f, brightOffset, 0f, 1f, 0f, 0f, brightOffset, 0f, 0f, 1f, 0f, brightOffset, 0f, 0f, 0f, 1f, 0f),
            m
        )
        return m
    }

    companion object {
        /** `multiply(a, b)` = طبّق b أولاً ثم a (كلاهما 4×5 بترتيب ColorMatrix). */
        fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(20)
            for (r in 0 until 4) {
                for (c in 0 until 5) {
                    var v = 0f
                    for (k in 0 until 4) v += a[r * 5 + k] * b[k * 5 + c]
                    if (c == 4) v += a[r * 5 + 4]
                    out[r * 5 + c] = v
                }
            }
            return out
        }

        internal fun approx(a: Float, b: Float, eps: Float = 0.01f): Boolean = abs(a - b) <= eps
    }
}

/**
 * فلاتر جاهزة: كل فلتر مجموعة قيم [Adjust]، فتبقى الشرائح قابلة للتعديل الدقيق
 * بعده ويُعلَّم الفلتر كمحدَّد فقط ما دامت القيم تطابقه تماماً.
 */
enum class FilterPreset(val adjust: Adjust) {
    NONE(Adjust()),
    VIVID(Adjust(contrast = 12, saturation = 35)),
    WARM(Adjust(warmth = 40)),
    COOL(Adjust(warmth = -40)),
    SEPIA(Adjust(brightness = 6, contrast = -10, saturation = -80, warmth = 35)),
    MONO(Adjust(saturation = -100)),
    NOIR(Adjust(contrast = 40, saturation = -100)),
    FADE(Adjust(brightness = 8, contrast = -35, saturation = -15)),
    VINTAGE(Adjust(brightness = 5, contrast = -15, saturation = -20, warmth = 25))
}
