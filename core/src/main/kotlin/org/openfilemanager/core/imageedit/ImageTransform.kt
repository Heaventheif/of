package org.openfilemanager.core.imageedit

import kotlin.math.roundToInt

/**
 * حالة تحويل الصورة غير المدمّجة (non-destructive): تُطبَّق عند العرض والتصدير
 * ولا تُمسّ الصورة الأصلية أبداً. الترتيب الداخلي ثابت:
 *
 *     قصّ ← تدوير حر ← أرباع تدوير ← قلب أفقي/رأسي ← الصورة الأصلية
 *
 * أي أن [preCropMatrix] يبدأ من الصورة الأصلية ويطبّق: القلب أولاً، ثم أرباع
 * التدوير، ثم التدوير الحر (مع نموّ اللوحة إلى صندوق إحاطة)، والقصّ إزاحة
 * أخيرة تختار نافذة من اللوحة قبل القصّ.
 *
 * ثوابت الحالة (يفرضها [normalized]):
 *  - لا قلب أفقي ورأسي معاً (يكافئ نصف دورة فيُدمج في [quarterTurns]).
 *  - [quarterTurns] داخل 0..3 و[freeRotation] داخل (-45, 45].
 *
 * كل الإحداثيات بإحداثيات بكسل الصورة الأصلية (doc space) — لا إحداثيات شاشة.
 */
data class ImageTransform(
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val freeRotation: Float = 0f,
    val crop: FloatRect? = null
) {

    /** زاوية التدوير الكلية بالدرجات (باتجاه الساعة) — بلا أثر القلب. */
    val totalRotationDegrees: Float
        get() = 90f * (((quarterTurns % 4) + 4) % 4) + freeRotation

    /** أبعاد لوحة ما قبل القصّ (بعد القلب والأرباع والتدوير الحر). */
    fun preCropSize(srcWidth: Float, srcHeight: Float): Pair<Float, Float> {
        var w = srcWidth
        var h = srcHeight
        repeat(((quarterTurns % 4) + 4) % 4) { val t = w; w = h; h = t }
        val rot = freeRotation
        if (rot != 0f) {
            val (bw, bh) = CropMath.rotatedBounds(w, h, rot)
            w = bw; h = bh
        }
        return Pair(w, h)
    }

    /** أبعاد المخرَج النهائي (نافذة القصّ إن وُجدت وإلا اللوحة كاملة). */
    fun finalSize(srcWidth: Float, srcHeight: Float): Pair<Float, Float> {
        val (pw, ph) = preCropSize(srcWidth, srcHeight)
        val c = crop ?: return Pair(pw, ph)
        return Pair(c.width, c.height)
    }

    /** مصفوفة: إحداثيات الصورة الأصلية -> إحداثيات لوحة ما قبل القصّ. */
    fun preCropMatrix(srcWidth: Float, srcHeight: Float): Affine2 {
        var m = Affine2.IDENTITY
        var w = srcWidth
        var h = srcHeight
        if (flipH) { m = m.then(Affine2.horizontalMirror(w)) }
        if (flipV) { m = m.then(Affine2.verticalMirror(h)) }
        repeat(((quarterTurns % 4) + 4) % 4) {
            m = m.then(Affine2.quarterTurnCw(w, h))
            val t = w; w = h; h = t
        }
        val rot = freeRotation
        if (rot != 0f) {
            val (bw, bh) = CropMath.rotatedBounds(w, h, rot)
            m = m.then(Affine2.translation(-w / 2f, -h / 2f))
            m = m.then(Affine2.rotationDegrees(rot))
            m = m.then(Affine2.translation(bw / 2f, bh / 2f))
        }
        return m
    }

    /** مصفوفة: إحداثيات الصورة الأصلية -> إحداثيات المخرَج النهائي (تشمل إزاحة القصّ). */
    fun fullMatrix(srcWidth: Float, srcHeight: Float): Affine2 {
        val m = preCropMatrix(srcWidth, srcHeight)
        val c = crop ?: return m
        return m.then(Affine2.translation(-c.left, -c.top))
    }

    /** الحالة بعد فرض الثوابت — يجب أن تمرّ كل حالة في الواجهة من هنا. */
    fun normalized(): ImageTransform {
        var turns = ((quarterTurns % 4) + 4) % 4
        var h = flipH
        var v = flipV
        var free = freeRotation
        if (h && v) { h = false; v = false; turns = (turns + 2) % 4 }
        // طيّ التدوير الحر خارج النطاق إلى أرباع
        if (free > 45f || free <= -45f) {
            val q = (free / 90f).roundToInt()
            turns = (((turns + q) % 4) + 4) % 4
            free -= q * 90f
        }
        if (kotlin.math.abs(free) < 0.01f) free = 0f
        return if (turns == quarterTurns && h == flipH && v == flipV && free == freeRotation) this
        else ImageTransform(turns, h, v, free, crop)
    }

    /** إضافة ربع دورة (delta موجب = باتجاه الساعة، بأي مقدار) في نهاية السلسلة. */
    fun appendedQuarterTurn(srcWidth: Float, srcHeight: Float, delta: Int): ImageTransform {
        val base = normalized()
        val (pw, ph) = base.preCropSize(srcWidth, srcHeight)
        val newCrop = base.crop?.let { c ->
            when (((delta % 4) + 4) % 4) {
                1 -> CropMath.rotateQuarterCw(c, pw, ph)
                3 -> CropMath.rotateQuarterCcw(c, pw, ph)
                2 -> CropMath.rotateHalf(c, pw, ph)
                else -> c
            }
        }
        val total = base.totalRotationDegrees + 90f * delta
        return decompose(total, base.flipH, base.flipV, newCrop)
    }

    /** إضافة قلب أفقي أو رأسي في نهاية السلسلة. */
    fun appendedFlip(srcWidth: Float, srcHeight: Float, horizontal: Boolean): ImageTransform {
        val base = normalized()
        val (pw, ph) = base.preCropSize(srcWidth, srcHeight)
        val newCrop = base.crop?.let { c ->
            if (horizontal) CropMath.mirrorHorizontally(c, pw) else CropMath.mirrorVertically(c, ph)
        }
        // Refl·R(θ)·D = R(-θ)·(Refl·D): تنعكس الزاوية الكلية ويتغير اتجاه القلب.
        val newTotal = -base.totalRotationDegrees
        return if (horizontal) {
            when {
                !base.flipH && !base.flipV -> decompose(newTotal, flipH = true, flipV = false, newCrop)
                base.flipH -> decompose(newTotal, flipH = false, flipV = false, newCrop)
                base.flipV -> decompose(newTotal + 180f, flipH = false, flipV = false, newCrop)
                else -> decompose(newTotal, flipH = true, flipV = false, newCrop)
            }
        } else {
            when {
                !base.flipH && !base.flipV -> decompose(newTotal, flipH = false, flipV = true, newCrop)
                base.flipV -> decompose(newTotal, flipH = false, flipV = false, newCrop)
                base.flipH -> decompose(newTotal + 180f, flipH = false, flipV = false, newCrop)
                else -> decompose(newTotal, flipH = false, flipV = true, newCrop)
            }
        }
    }

    /** تعيين زاوية التدوير الحر مباشرة (يتحكم بها المنزلق في الواجهة). */
    fun withFreeRotation(degrees: Float): ImageTransform {
        val clamped = degrees.coerceIn(-45f, 45f)
        return copy(freeRotation = clamped).normalized()
    }

    fun withCrop(rect: FloatRect?): ImageTransform = copy(crop = rect).normalized()

    companion object {
        /** تفكيك زاوية كلية + اتجاه قلب إلى الحقول الأربعة مع فرض الثوابت. */
        internal fun decompose(
            totalDegrees: Float, flipH: Boolean, flipV: Boolean, crop: FloatRect?
        ): ImageTransform {
            var total = ((totalDegrees % 360f) + 360f) % 360f
            var turns = (total / 90f).toInt() // 0..3 بعد القسمة أعلاه
            var free = total - 90f * turns
            if (free > 45f) { turns += 1; free -= 90f }
            turns = ((turns % 4) + 4) % 4
            total = 0f // (للوضوح فقط — لا استخدام لاحق)
            if (kotlin.math.abs(free) < 0.01f) free = 0f
            return ImageTransform(turns, flipH, flipV, free, crop)
        }
    }
}

/**
 * تاريخ تحرير عام يخزّن حالات خفيفة (لا نسخ من الصور النقطية). الالتزام
 * بترتيب: record قبل كل تعديل، وundo/redo تُعيد الحالة السابقة/التالية.
 */
class EditHistory<T>(private val capacity: Int = 60) {

    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** تسجيل الحالة الحالية قبل تطبيق تعديل جديد (يمسح مستقبل الإعادة). */
    fun record(state: T) {
        past.addLast(state)
        while (past.size > capacity) past.removeFirst()
        future.clear()
    }

    /** إرجاع آخر حالة مسجّلة؛ يعاد null إذا لم يكن هناك ما يُلغى. */
    fun undo(current: T): T? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return previous
    }

    /** إعادة الحالة التالية (الأحدث تراجعاً)؛ يعاد null إذا لم يكن هناك ما يُعاد. */
    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }

    fun clear() { past.clear(); future.clear() }

    /** للاختبار فقط: عدد الحالات المسجّلة القابلة للتراجع. */
    fun pastSizeForTest(): Int = past.size
}
