package org.openfilemanager.core.imageedit

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * شكل حدود القصّ عند السحب: حر (رسم بالإصبع) أو صندوق/مربع/قطع ناقص/دائرة تُسحب
 * على القماش. الأشكال كلها تتحوّل إلى مضلّع نقاطي فتُعالَج كالقصّ الحر تماماً.
 */
enum class CutShape { FREE, RECT, SQUARE, ELLIPSE, CIRCLE }

/** توليد مضلّعات أشكال القصّ من نقطتي السحب (بإحداثيات المستند). */
object CutShapes {

    const val CURVE_POINTS = 96

    private fun box(x1: Float, y1: Float, x2: Float, y2: Float): List<Pair<Float, Float>> = listOf(
        Pair(min(x1, x2), min(y1, y2)),
        Pair(max(x1, x2), min(y1, y2)),
        Pair(max(x1, x2), max(y1, y2)),
        Pair(min(x1, x2), max(y1, y2))
    )

    fun polygon(shape: CutShape, x1: Float, y1: Float, x2: Float, y2: Float): List<Pair<Float, Float>> = when (shape) {
        CutShape.RECT -> box(x1, y1, x2, y2)
        CutShape.SQUARE -> {
            val dx = x2 - x1
            val dy = y2 - y1
            val side = max(abs(dx), abs(dy))
            // الضلعان متساويان مع الحفاظ على اتجاه السحب لكل محور
            box(x1, y1, x1 + if (dx < 0f) -side else side, y1 + if (dy < 0f) -side else side)
        }
        CutShape.ELLIPSE -> {
            val cx = (x1 + x2) / 2f
            val cy = (y1 + y2) / 2f
            val rx = abs(x2 - x1) / 2f
            val ry = abs(y2 - y1) / 2f
            curve(CURVE_POINTS) { a -> Pair(cx + rx * kotlin.math.cos(a), cy + ry * kotlin.math.sin(a)) }
        }
        // الدائرة مركزها نقطة البدء ونصف قطرها مسافة السحب — أدق في التحكم بالحجم
        CutShape.CIRCLE -> {
            val r = hypot(x2 - x1, y2 - y1)
            curve(CURVE_POINTS) { a -> Pair(x1 + r * kotlin.math.cos(a), y1 + r * kotlin.math.sin(a)) }
        }
        CutShape.FREE -> emptyList()
    }

    /** هل الشكل بحاجة إلى مسافة سحب دنيا قبل أن يصبح صالحاً؟ */
    fun isValid(points: List<Pair<Float, Float>>, minSize: Float): Boolean {
        if (points.size < 3) return false
        val w = points.maxOf { it.first } - points.minOf { it.first }
        val h = points.maxOf { it.second } - points.minOf { it.second }
        return w >= minSize && h >= minSize
    }

    private inline fun curve(n: Int, f: (Float) -> Pair<Float, Float>): List<Pair<Float, Float>> {
        val step = 2.0 * Math.PI / n
        return List(n) { i -> f((step * i).toFloat()) }
    }
}
