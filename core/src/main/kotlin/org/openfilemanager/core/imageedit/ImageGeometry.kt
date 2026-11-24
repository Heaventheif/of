package org.openfilemanager.core.imageedit

import kotlin.math.abs

/**
 * مستطيل بأبعاد عائمة، مستقل تماماً عن أي مكتبة واجهات — نظير androidx RectF
 * لكن في وحدة :core حتى تُختبر عمليات القصّ على JVM.
 */
data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun coerceIn(boundsWidth: Float, boundsHeight: Float): FloatRect = FloatRect(
        left.coerceIn(0f, boundsWidth), top.coerceIn(0f, boundsHeight),
        right.coerceIn(0f, boundsWidth), bottom.coerceIn(0f, boundsHeight)
    )

    /** نفس الحجم ونفس المركز لكن مضبوطاً داخل الحدود. */
    fun clampedKeepingSize(boundsWidth: Float, boundsHeight: Float): FloatRect {
        var l = left; var t = top; var r = right; var b = bottom
        if (l < 0f) { r -= l; l = 0f }
        if (t < 0f) { b -= t; t = 0f }
        if (r > boundsWidth) { l -= (r - boundsWidth); r = boundsWidth }
        if (b > boundsHeight) { t -= (b - boundsHeight); b = boundsHeight }
        return FloatRect(
            l.coerceAtLeast(0f), t.coerceAtLeast(0f),
            r.coerceAtMost(boundsWidth), b.coerceAtMost(boundsHeight)
        )
    }

    companion object {
        fun ltrb(left: Float, top: Float, right: Float, bottom: Float) =
            FloatRect(left, top, right, bottom)

        fun xywh(x: Float, y: Float, w: Float, h: Float) = FloatRect(x, y, x + w, y + h)
    }
}

/**
 * رياضيات القصّ على مستوى الصورة الأصلية (لا إحداثيات الشاشة إطلاقاً).
 * كل الدوال نقية وقابلة للاختبار على JVM.
 */
object CropMath {

    /** أصغر ضلع مسموح لمنطقة القصّ بوحدات بكسل الصورة. */
    const val MIN_SIZE = 16f

    /**
     * أقرب نسبة عرض:ارتفاع شائعة لنسبة معطاة، أو null إذا لم تكن شائعة.
     * مفيد لعرض التسمية الصحيحة لمنطقة قصّ موجودة.
     */
    fun commonAspectOf(ratio: Float): String? {
        val presets = listOf(1f to "1:1", 4f / 3f to "4:3", 3f / 4f to "3:4", 16f / 9f to "16:9", 9f / 16f to "9:16")
        return presets.firstOrNull { abs(it.first - ratio) < 0.01f }?.second
    }

    /**
     * أكبر مستطيل بنسبة aspect يتركّز داخل [rect]. إذا كانت المساحة لا تكفي
     * يُخفَّض الحجم مع الحفاظ على النسبة والاتجاه المطلوب.
     */
    fun fitAspect(rect: FloatRect, aspect: Float?): FloatRect {
        if (aspect == null || aspect <= 0f || rect.isEmpty) return rect
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        var w = rect.width
        var h = w / aspect
        if (h > rect.height) { h = rect.height; w = h * aspect }
        return FloatRect.xywh(cx - w / 2f, cy - h / 2f, w, h)
    }

    /** تطبيق النسبة على مستطيل داخل حدود معينة، مع حد أدنى للحجم. */
    fun withAspectInside(
        rect: FloatRect, boundsWidth: Float, boundsHeight: Float, aspect: Float?
    ): FloatRect {
        val fitted = fitAspect(rect, aspect).coerceIn(boundsWidth, boundsHeight)
        val min = MIN_SIZE
        val w = fitted.width.coerceAtLeast(min.coerceAtMost(boundsWidth))
        val h = fitted.height.coerceAtLeast(min.coerceAtMost(boundsHeight))
        val cx = (fitted.left + fitted.right) / 2f
        val cy = (fitted.top + fitted.bottom) / 2f
        return FloatRect.xywh(cx - w / 2f, cy - h / 2f, w, h).clampedKeepingSize(boundsWidth, boundsHeight)
    }

    /**
     * قصّ افتراضي مركّز: أوسع مستطيل بنسبة aspect داخل الصورة كاملة.
     * يعاد null إذا لم تُطلب نسبة (يعني القصّ الحر).
     */
    fun centeredAspectCrop(
        imageWidth: Float, imageHeight: Float, aspect: Float?
    ): FloatRect? {
        if (aspect == null || aspect <= 0f) return null
        val full = FloatRect.xywh(0f, 0f, imageWidth, imageHeight)
        return withAspectInside(full, imageWidth, imageHeight, aspect)
    }

    /** تدوير مستطيل مع لوحته ربع دورة باتجاه الساعة: (x,y) -> (h-y, x). */
    fun rotateQuarterCw(rect: FloatRect, oldWidth: Float, oldHeight: Float): FloatRect =
        FloatRect(
            left = oldHeight - rect.bottom,
            top = rect.left,
            right = oldHeight - rect.top,
            bottom = rect.right
        )

    /** تدوير ربع دورة عكس الساعة: (x,y) -> (y, w-x). */
    fun rotateQuarterCcw(rect: FloatRect, oldWidth: Float, oldHeight: Float): FloatRect =
        FloatRect(
            left = rect.top,
            top = oldWidth - rect.right,
            right = rect.bottom,
            bottom = oldWidth - rect.left
        )

    /** نصف دورة: (x,y) -> (w-x, h-y). */
    fun rotateHalf(rect: FloatRect, oldWidth: Float, oldHeight: Float): FloatRect =
        FloatRect(
            left = oldWidth - rect.right,
            top = oldHeight - rect.bottom,
            right = oldWidth - rect.left,
            bottom = oldHeight - rect.top
        )

    /** قلب أفقي داخل لوحة عرضها width. */
    fun mirrorHorizontally(rect: FloatRect, width: Float): FloatRect =
        FloatRect(width - rect.right, rect.top, width - rect.left, rect.bottom)

    /** قلب رأسي داخل لوحة ارتفاعها height. */
    fun mirrorVertically(rect: FloatRect, height: Float): FloatRect =
        FloatRect(rect.left, height - rect.bottom, rect.right, height - rect.top)

    /** أبعاد أصغر مستطيل يحوي لوحة w×h بعد تدويرها degrees حول مركزها. */
    fun rotatedBounds(width: Float, height: Float, degrees: Float): Pair<Float, Float> {
        val rad = Math.toRadians(degrees.toDouble())
        val c = abs(kotlin.math.cos(rad)).toFloat()
        val s = abs(kotlin.math.sin(rad)).toFloat()
        return Pair(width * c + height * s, width * s + height * c)
    }
}
