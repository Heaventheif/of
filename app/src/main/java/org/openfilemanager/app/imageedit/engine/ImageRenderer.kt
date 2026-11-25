package org.openfilemanager.app.imageedit.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.openfilemanager.app.imageedit.model.BrushStyle
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.ShapeKind
import org.openfilemanager.app.imageedit.model.EditorFontFamily
import org.openfilemanager.app.imageedit.model.TextAlignment
import org.openfilemanager.core.imageedit.Adjust
import org.openfilemanager.core.imageedit.Affine2
import org.openfilemanager.core.imageedit.FloatRect
import org.openfilemanager.core.imageedit.ImageTransform
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * محرّك العرض: يرسم الصورة الأساس مع حالة التحويل (قصّ/تدوير/قلب) وكل الطبقات
 * في تمريرة واحدة. نفس الكود يُستخدم للمعاينة (على بت ماب مصغّرة) وللتصدير
 * (على قرار كامل) — فرق المقياس فقط عبر [docScale].
 *
 * الأصل لا يُعدَّل أبداً: التحويل حالة تُطبَّق عند الرسم فقط.
 */
object ImageRenderer {

    private const val TEXT_PADDING = 0.25f // نسبة الحشو حول النص من حجمه

    // ---------------------------------------------------------------------
    // تحويلات مساعدة

    /** تحويل [Affine2] إلى مصفوفة أندرويد (نفس ترتيب القيم). */
    fun toAndroidMatrix(m: Affine2): Matrix = Matrix().apply {
        setValues(floatArrayOf(m.a, m.c, m.tx, m.b, m.d, m.ty, 0f, 0f, 1f))
    }

    /** مصفوفة كاملة: إحداثيات المستند -> بكسل المخرَج (تشمل المقياس). */
    fun outputMatrix(transform: ImageTransform, docW: Float, docH: Float, docScale: Float): Affine2 =
        transform.fullMatrix(docW, docH).then(Affine2.scaling(docScale, docScale))

    /** مثلها لكن بلا قلب الصورة — لاتجاه الطبقات النصية/الصورية كي لا تنعكس حروفها. */
    fun uprightMatrix(transform: ImageTransform, docW: Float, docH: Float, docScale: Float): Affine2 {
        val noFlip = ImageTransform(
            quarterTurns = transform.quarterTurns,
            freeRotation = transform.freeRotation,
            crop = transform.crop
        )
        return outputMatrix(noFlip, docW, docH, docScale)
    }

    // ---------------------------------------------------------------------
    // الرسم

    /**
     * رسم الحالة كاملة على بت ماب جديدة.
     * @param base بت ماب الصورة الأصلية المعترضة (معاينة أو قرار كامل).
     * @param docScale بكسل المخرَج لكل وحدة مستند (preview: < 1، تصدير: 1).
     * @param adjust تعديلات الصورة الأساس (الإضاءة/الألوان/الفلاتر) — تُطبَّق كمصفوفة ألوان
     *  عند الرسم فلا تُعدَّل البكسلات؛ التظليل الحوافي تدرّج يُرسم فوق الصورة قبل الطبقات.
     */
    fun render(
        base: Bitmap,
        transform: ImageTransform,
        layers: List<EditLayer>,
        docScale: Float,
        opaqueBackground: Boolean,
        adjust: Adjust = Adjust()
    ): Bitmap {
        val docW = base.width / docScale
        val docH = base.height / docScale
        val (fw, fh) = transform.finalSize(docW, docH)
        val outW = max(1, (fw * docScale).toInt())
        val outH = max(1, (fh * docScale).toInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (opaqueBackground) canvas.drawColor(Color.BLACK)

        val full = outputMatrix(transform, docW, docH, docScale)
        val upright = if (transform.flipH || transform.flipV)
            uprightMatrix(transform, docW, docH, docScale) else full

        // 1) الصورة الأساس عبر التحويل الكامل (قلب الصورة يشمل البكسلات نفسها)
        val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (!adjust.isIdentity) {
            basePaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(adjust.colorMatrix()))
        }
        canvas.drawBitmap(base, toAndroidMatrix(full), basePaint)
        if (adjust.vignette > 0) drawVignette(canvas, outW, outH, adjust.vignette)

        // 2) الطبقات فوقها
        for (layer in layers) {
            if (!layer.visible) continue
            when (layer) {
                is EditLayer.Image -> drawImageLayer(canvas, layer, full, upright)
                is EditLayer.Text -> drawTextLayer(canvas, layer, full, upright, docW)
                is EditLayer.Stroke -> drawStrokeLayer(canvas, layer, full)
                is EditLayer.Shape -> drawShapeLayer(canvas, layer, full, upright)
            }
        }
        return out
    }

    private fun paintFor(alpha: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
    }

    /**
     * تظليل حوافي: تدرّج شعاعي شفاف في المركز يسودّ نحو الزوايا. يُرسم فوق الصورة
     * قبل الطبقات كي لا يُعتم النص والملصقات. [strength] 0..100.
     */
    private fun drawVignette(canvas: Canvas, w: Int, h: Int, strength: Int) {
        val s = strength.coerceIn(0, 100) / 100f
        val cx = w / 2f
        val cy = h / 2f
        // نصف القطر حتى أبعد زاوية: الظلام يبدأ من 45% ويبلغ أقصاه عند الزاوية
        val radius = hypot(cx, cy) * (1.35f - 0.35f * s)
        val shader = RadialGradient(
            cx, cy, radius.coerceAtLeast(1f),
            intArrayOf(Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            alpha = (255 * 0.75f * s).toInt().coerceIn(0, 255)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /**
     * (تصحيح) ترتيب التركيب. `a.then(b)` في [Affine2] يعني «طبّق a أولاً ثم b».
     * الشيفرة القديمة كانت تكتب السلاسل بترتيب ضرب المصفوفات (الأخير يُطبَّق أولاً)،
     * فكانت الصور والنصوص والأشكال تُرسم في مكان خاطئ أو تختفي عند التدوير، وتنفصل
     * عن إطار التحكم الذي كان محسوباً بشكل صحيح.
     *
     * يضع محتوى الطبقة ([inner]: إحداثيات محلية مركزها الأصل) حول مركزها بحيث يُحوَّل
     * المركز بالتحويل الكامل (يشمل القلب) بينما يبقى اتجاه المحتوى معتدلاً (بلا قلب)
     * كي لا تنعكس الحروف والصور المضافة.
     */
    private fun uprightPlacement(
        inner: Affine2, full: Affine2, upright: Affine2, cx: Float, cy: Float
    ): Affine2 {
        val linear = Affine2(upright.a, upright.b, upright.c, upright.d, 0f, 0f)
        val pc = full.apply(cx, cy)
        return inner.then(linear).then(Affine2.translation(pc.first, pc.second))
    }

    private fun drawImageLayer(canvas: Canvas, layer: EditLayer.Image, full: Affine2, upright: Affine2) {
        val bmp = layer.bitmap
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return
        val s = layer.docWidth / bmp.width
        val inner = Affine2.translation(-bmp.width / 2f, -bmp.height / 2f)
            .then(Affine2.scaling(s * if (layer.flipH) -1f else 1f, s * if (layer.flipV) -1f else 1f))
            .then(Affine2.rotationDegrees(layer.rotation))
        val total = uprightPlacement(inner, full, upright, layer.centerX, layer.centerY)
        val p = paintFor(layer.opacity).apply { isFilterBitmap = true; isDither = true }
        canvas.drawBitmap(bmp, toAndroidMatrix(total), p)
    }

    private fun typefaceOf(layer: EditLayer.Text): Typeface =
        EditorFonts.resolve(layer.fontFamily, layer.bold, layer.italic)

    private fun autoOutlineColor(textColor: Int): Int {
        val lum = (Color.red(textColor) * 0.299f + Color.green(textColor) * 0.587f +
            Color.blue(textColor) * 0.114f) / 255f
        return if (lum > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun textPaintOf(layer: EditLayer.Text, style: Paint.Style, alpha: Int): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.color.toInt()
            textSize = layer.fontSizePx
            typeface = typefaceOf(layer)
            isUnderlineText = layer.underline
            isStrikeThruText = layer.strike
            letterSpacing = layer.letterSpacing
            this.alpha = alpha
            if (style == Paint.Style.STROKE) {
                this.style = Paint.Style.STROKE
                strokeWidth = if (layer.outlineWidthPx > 0f) layer.outlineWidthPx else layer.fontSizePx * 0.08f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = layer.outlineColor?.toInt() ?: autoOutlineColor(layer.color.toInt())
                this.alpha = alpha
            } else if (layer.shadow) {
                setShadowLayer(
                    layer.fontSizePx * 0.12f, layer.fontSizePx * 0.05f, layer.fontSizePx * 0.05f,
                    layer.shadowColor.toInt()
                )
            }
        }

    /** أقصى عرض لسطر النص بوحدات المستند: نسبة من عرض الصورة (لا يقل عن حجم الخط). */
    fun textMaxWidth(layer: EditLayer.Text, docW: Float): Float =
        max(layer.fontSizePx, docW * layer.boxWidthFrac.coerceIn(0.1f, 1f))

    @Suppress("DEPRECATION")
    private fun buildLayout(layer: EditLayer.Text, paint: TextPaint, width: Int): StaticLayout {
        val text = layer.text.ifEmpty { " " }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(
                when (layer.alignment) {
                    TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
                    TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                    TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
                }
            )
            .setLineSpacing(0f, layer.lineSpacing.coerceIn(0.5f, 3f))
            .setIncludePad(true)
            .build()
    }

    /**
     * (تصحيح) بناء تخطيط النص. السبب الجذري لاختفاء النصوص: كان التخطيط يُبنى بعرض
     * عريض جداً (حجم الخط × 40) مع محاذاة وسط/نهاية، بينما يُرسم الصندوق بعرض أضيق
     * هو عرض أطول سطر — فيُزاح النص (وكل النص العربي الذي يُحاذى لليمين تلقائياً)
     * خارج الصورة. الآن نقيس أطول سطر ضمن العرض الأقصى ثم نعيد البناء بعرض الصندوق
     * الفعلي، فتقع المحاذاة داخل الصندوق.
     */
    fun textLayout(
        layer: EditLayer.Text,
        maxDocWidth: Float,
        alpha: Int = 255,
        style: Paint.Style = Paint.Style.FILL
    ): StaticLayout {
        val paint = textPaintOf(layer, style, alpha)
        val wide = buildLayout(layer, paint, ceil(max(1f, maxDocWidth)).toInt())
        var w = 0f
        for (i in 0 until wide.lineCount) w = max(w, wide.getLineWidth(i))
        val finalWidth = ceil(w).toInt().coerceAtLeast(1) + 2
        return if (finalWidth == wide.width) wide else buildLayout(layer, paint, finalWidth)
    }

    /** قياس صندوق محتوى النص بإحداثيات المستند (بلا تطبيق تحويل الصورة). */
    fun textContentSize(layer: EditLayer.Text, maxDocWidth: Float): Pair<Float, Float> {
        val sl = textLayout(layer, maxDocWidth)
        return Pair(sl.width.toFloat(), sl.height.toFloat())
    }

    private fun drawTextLayer(
        canvas: Canvas, layer: EditLayer.Text, full: Affine2, upright: Affine2, docW: Float
    ) {
        val maxW = textMaxWidth(layer, docW)
        val alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
        val fillLayout = textLayout(layer, maxW, alpha)
        val w = fillLayout.width.toFloat()
        val h = fillLayout.height.toFloat()
        if (w <= 0f || h <= 0f) return

        val total = uprightPlacement(
            Affine2.rotationDegrees(layer.rotation), full, upright, layer.centerX, layer.centerY
        )

        canvas.save()
        canvas.concat(toAndroidMatrix(total))
        canvas.translate(-w / 2f, -h / 2f)

        if (layer.bgColor != null) {
            val pad = layer.fontSizePx * TEXT_PADDING
            val bgArgb = layer.bgColor.toInt()
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgArgb
                // شفافية البطاقة (ألفا لونها) × شفافية الطبقة
                this.alpha = (Color.alpha(bgArgb) * layer.opacity).toInt().coerceIn(0, 255)
            }
            canvas.drawRoundRect(
                RectF(-pad, -pad, w + pad, h + pad),
                layer.fontSizePx * 0.35f, layer.fontSizePx * 0.35f, bg
            )
        }

        if (layer.outline) {
            textLayout(layer, maxW, alpha, Paint.Style.STROKE).draw(canvas)
        }
        fillLayout.draw(canvas)
        canvas.restore()
    }

    /**
     * ضربة فرشاة. لكل نمط هيئته:
     * - PEN: خط حاد بلون كامل.
     * - MARKER: خط عريض شبه شفاف كالفلوماستر.
     * - NEON: توهّج مموّه عريض تحت خط داخلي فاتح.
     */
    private fun drawStrokeLayer(canvas: Canvas, layer: EditLayer.Stroke, m: Affine2) {
        val width = layer.widthPx * m.meanScale
        if (layer.points.size == 1) {
            // نقطة واحدة: قرص صغير بنمط الفرشاة نفسه
            val p = m.apply(layer.points[0].first, layer.points[0].second)
            for (paint in strokePaints(layer, width)) canvas.drawCircle(p.first, p.second, width / 2f, paint)
            return
        }
        val path = Path()
        var first = true
        for (pt in layer.points) {
            val mapped = m.apply(pt.first, pt.second)
            if (first) { path.moveTo(mapped.first, mapped.second); first = false }
            else path.lineTo(mapped.first, mapped.second)
        }
        for (paint in strokePaints(layer, width)) canvas.drawPath(path, paint)
    }

    /** دهانات الضربة بترتيب رسمها (الأرضية ثم السطح). */
    private fun strokePaints(layer: EditLayer.Stroke, width: Float): List<Paint> {
        fun stroke(alpha: Float, w: Float) = paintFor(layer.opacity * alpha).apply {
            color = layer.color.toInt()
            style = Paint.Style.STROKE
            strokeWidth = w.coerceAtLeast(0.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        return when (layer.style) {
            BrushStyle.PEN -> listOf(stroke(1f, width))
            BrushStyle.MARKER -> listOf(stroke(0.4f, width * 2.2f))
            BrushStyle.NEON -> listOf(
                stroke(0.85f, width * 2.6f).apply {
                    maskFilter = BlurMaskFilter(width * 0.9f, BlurMaskFilter.Blur.NORMAL)
                },
                stroke(0.35f, width * 2.2f),
                // القلب الفاتح: اللون الأصلي مرفوع نحو الأبيض
                stroke(1f, width * 0.55f).apply {
                    color = lighten(layer.color, 0.65f)
                }
            )
        }
    }

    /** يقرّب اللون من الأبيض بنسبة [amount] 0..1. */
    private fun lighten(argb: Long, amount: Float): Int {
        val c = argb.toInt()
        fun ch(v: Int): Int = (v + (255 - v) * amount).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (ch(Color.red(c)) shl 16) or (ch(Color.green(c)) shl 8) or ch(Color.blue(c))
    }

    // ---------------------------------------------------------------------
    // الأشكال

    /**
     * مسار الأشكال المغلقة داخل صندوق. عام كي تستعمله أيقونات لوحة الأشكال نفسها
     * فتطابق ما يُرسم على الصورة. يعاد null للخطوط والأسهم والبالون.
     */
    fun shapePath(kind: ShapeKind, l: Float, t: Float, r: Float, b: Float): Path? {
        val w = r - l
        val h = b - t
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val p = Path()
        fun poly(vararg pts: Float) {
            p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
            p.close()
        }
        when (kind) {
            ShapeKind.RECT, ShapeKind.SQUARE -> p.addRect(RectF(l, t, r, b), Path.Direction.CW)
            ShapeKind.ROUNDED_RECT -> {
                val rad = minOf(w, h) * 0.22f
                p.addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)
            }
            ShapeKind.CIRCLE, ShapeKind.ELLIPSE -> p.addOval(RectF(l, t, r, b), Path.Direction.CW)
            ShapeKind.TRIANGLE -> poly(cx, t, r, b, l, b)
            ShapeKind.RIGHT_TRIANGLE -> poly(l, t, r, b, l, b)
            ShapeKind.DIAMOND -> poly(cx, t, r, cy, cx, b, l, cy)
            ShapeKind.PENTAGON, ShapeKind.HEXAGON, ShapeKind.STAR -> {
                val n = if (kind == ShapeKind.PENTAGON) 5 else if (kind == ShapeKind.HEXAGON) 6 else 10
                val start = if (kind == ShapeKind.HEXAGON) 0.0 else -90.0
                val step = 360.0 / n
                for (i in 0 until n) {
                    val rr = if (kind == ShapeKind.STAR && i % 2 == 1) 0.42f else 1f
                    val a = Math.toRadians(start + step * i)
                    val x = cx + (w / 2f) * rr * cos(a).toFloat()
                    val y = cy + (h / 2f) * rr * sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            ShapeKind.HEART -> {
                p.moveTo(cx, t + h * 0.30f)
                p.cubicTo(cx, t, l, t, l, t + h * 0.35f)
                p.cubicTo(l, t + h * 0.65f, cx, t + h * 0.80f, cx, b)
                p.cubicTo(cx, t + h * 0.80f, r, t + h * 0.65f, r, t + h * 0.35f)
                p.cubicTo(r, t, cx, t, cx, t + h * 0.30f)
                p.close()
            }
            ShapeKind.PLUS -> poly(
                l + w / 3f, t, r - w / 3f, t, r - w / 3f, t + h / 3f, r, t + h / 3f,
                r, b - h / 3f, r - w / 3f, b - h / 3f, r - w / 3f, b, l + w / 3f, b,
                l + w / 3f, b - h / 3f, l, b - h / 3f, l, t + h / 3f, l + w / 3f, t + h / 3f
            )
            ShapeKind.CHEVRON -> poly(
                l, t, r - w * 0.35f, t, r, cy, r - w * 0.35f, b, l, b, l + w * 0.35f, cy
            )
            ShapeKind.BLOCK_ARROW -> poly(
                l, t + h * 0.25f, r - w * 0.4f, t + h * 0.25f, r - w * 0.4f, t, r, cy,
                r - w * 0.4f, b, r - w * 0.4f, b - h * 0.25f, l, b - h * 0.25f
            )
            ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW, ShapeKind.BUBBLE -> return null
        }
        return p
    }

    private fun drawShapeLayer(canvas: Canvas, layer: EditLayer.Shape, full: Affine2, upright: Affine2) {
        // (تصحيح) الهندسة تُرسم بإحداثيات المستند تحت مصفوفة (تدوير الطبقة حول مركزها
        // ثم التحويل الكامل) — فيدور الشكل كاملاً ويتحجم سمك خطه مع الصورة، وكان
        // الشكل سابقاً يُرسم كصندوق محوري ويختفي أو ينزاح.
        val rot = Affine2.translation(-layer.centerX, -layer.centerY)
            .then(Affine2.rotationDegrees(layer.rotation))
            .then(Affine2.translation(layer.centerX, layer.centerY))
        val geom = rot.then(full)

        val sw = layer.strokeWidthPx.coerceAtLeast(0.5f)
        val paint = paintFor(layer.opacity).apply {
            color = layer.strokeColor.toInt()
            style = Paint.Style.STROKE
            strokeWidth = sw
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = layer.fillColor?.let {
            paintFor(layer.opacity).apply { color = it.toInt(); style = Paint.Style.FILL }
        }

        val left = minOf(layer.x1, layer.x2); val top = minOf(layer.y1, layer.y2)
        val right = maxOf(layer.x1, layer.x2); val bottom = maxOf(layer.y1, layer.y2)
        val rect = RectF(left, top, right, bottom)

        canvas.save()
        canvas.concat(toAndroidMatrix(geom))
        when (layer.kind) {
            ShapeKind.LINE -> canvas.drawLine(layer.x1, layer.y1, layer.x2, layer.y2, paint)
            ShapeKind.ARROW -> {
                canvas.drawLine(layer.x1, layer.y1, layer.x2, layer.y2, paint)
                drawArrowHead(canvas, layer.x1, layer.y1, layer.x2, layer.y2, paint)
            }
            ShapeKind.DOUBLE_ARROW -> {
                canvas.drawLine(layer.x1, layer.y1, layer.x2, layer.y2, paint)
                drawArrowHead(canvas, layer.x1, layer.y1, layer.x2, layer.y2, paint)
                drawArrowHead(canvas, layer.x2, layer.y2, layer.x1, layer.y1, paint)
            }
            ShapeKind.BUBBLE -> {
                val r = minOf(rect.width(), rect.height()) * 0.3f
                fillPaint?.let { canvas.drawRoundRect(rect, r, r, it) }
                canvas.drawRoundRect(rect, r, r, paint)
                // ذيل البالون من الحافة السفلية نحو اليسار-الأسفل
                val tail = minOf(rect.width(), rect.height()) * 0.35f
                val tipX = rect.centerX() - tail * 0.6f
                val tipY = bottom + tail
                val baseL = rect.centerX() - tail * 0.5f
                val baseR = rect.centerX() + tail * 0.25f
                val tailPath = Path()
                tailPath.moveTo(baseL, bottom - sw * 0.5f)
                tailPath.lineTo(tipX, tipY)
                tailPath.lineTo(baseR, bottom - sw * 0.5f)
                tailPath.close()
                fillPaint?.let { canvas.drawPath(tailPath, it) }
                canvas.drawPath(tailPath, paint)
            }
            else -> {
                val path = shapePath(layer.kind, left, top, right, bottom)
                if (path != null) {
                    fillPaint?.let { canvas.drawPath(path, it) }
                    canvas.drawPath(path, paint)
                }
            }
        }
        canvas.restore()

        // نص البالون معتدل الاتجاه (لا ينعكس مع قلب الصورة)
        if (layer.kind == ShapeKind.BUBBLE) {
            val text = layer.bubbleText
            if (!text.isNullOrBlank() && rect.width() > 4f && rect.height() > 4f) {
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layer.textColor.toInt()
                    textSize = minOf(rect.width(), rect.height()) * 0.18f
                    this.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                }
                val sl = StaticLayout.Builder
                    .obtain(text, 0, text.length, tp, max(1, (rect.width() * 0.8f).toInt()))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
                val center = geom.apply(rect.centerX(), rect.centerY())
                val linear = Affine2(upright.a, upright.b, upright.c, upright.d, 0f, 0f)
                val total = Affine2.rotationDegrees(layer.rotation).then(linear)
                    .then(Affine2.translation(center.first, center.second))
                canvas.save()
                canvas.concat(toAndroidMatrix(total))
                canvas.translate(-sl.width / 2f, -sl.height / 2f)
                sl.draw(canvas)
                canvas.restore()
            }
        }
    }

    /** رأس سهم عند (x2,y2) قادماً من (x1,y1) — بوحدات المستند. */
    private fun drawArrowHead(
        canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint
    ) {
        val angle = atan2(y2 - y1, x2 - x1)
        val len = max(paint.strokeWidth * 3.5f, hypot(x2 - x1, y2 - y1) * 0.12f)
        val spread = Math.toRadians(26.0).toFloat()
        val a1 = (angle + Math.PI + spread).toFloat()
        val a2 = (angle + Math.PI - spread).toFloat()
        val path = Path()
        path.moveTo(x2, y2)
        path.lineTo(x2 + len * cos(a1), y2 + len * sin(a1))
        path.moveTo(x2, y2)
        path.lineTo(x2 + len * cos(a2), y2 + len * sin(a2))
        canvas.drawPath(path, paint)
    }

    // ---------------------------------------------------------------------
    // الهندسة للتحديد والالتقاط

    /**
     * زوايا صندوق إحاطة الطبقة بإحداثيات المستند (بلا تحويل الصورة، لكن
     * بمساحة ما قبل أي قصّ/تدوير). يستخدمها القماش لرسم إطار التحديد بعد
     * تمرير النقاط عبر مصفوفة العرض.
     */
    fun layerDocCorners(layer: EditLayer, docW: Float, docH: Float): List<Pair<Float, Float>> {
        val box: FloatRect = when (layer) {
            is EditLayer.Text -> {
                val (w, h) = textContentSize(layer, textMaxWidth(layer, docW))
                val pad = layer.fontSizePx * TEXT_PADDING
                FloatRect.xywh(layer.centerX - w / 2 - pad, layer.centerY - h / 2 - pad, w + pad * 2, h + pad * 2)
            }
            is EditLayer.Image -> {
                val aspect = if (layer.bitmap.height > 0)
                    layer.bitmap.height.toFloat() / layer.bitmap.width else 1f
                val h = layer.docWidth * aspect
                FloatRect.xywh(layer.centerX - layer.docWidth / 2, layer.centerY - h / 2, layer.docWidth, h)
            }
            is EditLayer.Stroke -> {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (p in layer.points) {
                    l = minOf(l, p.first); t = minOf(t, p.second)
                    r = maxOf(r, p.first); b = maxOf(b, p.second)
                }
                if (l > r) return emptyList()
                val pad = layer.widthPx / 2
                FloatRect(l - pad, t - pad, r + pad, b + pad)
            }
            is EditLayer.Shape -> {
                // هامش حول الشكل؛ الخطوط والأسهم صندوقها منعدم الارتفاع فتُعطى سماكة لمس
                val pad = if (layer.kind.isLinear) layer.strokeWidthPx * 2f else layer.strokeWidthPx / 2f
                FloatRect(
                    minOf(layer.x1, layer.x2) - pad, minOf(layer.y1, layer.y2) - pad,
                    maxOf(layer.x1, layer.x2) + pad, maxOf(layer.y1, layer.y2) + pad
                )
            }
        }
        // قلب الصندوق حول المركز حسب تدوير الطبقة
        val rad = Math.toRadians(layer.rotation.toDouble())
        val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        fun rotAroundCenter(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - layer.centerX; val dy = y - layer.centerY
            return Pair(layer.centerX + dx * c - dy * s, layer.centerY + dx * s + dy * c)
        }
        return listOf(
            rotAroundCenter(box.left, box.top), rotAroundCenter(box.right, box.top),
            rotAroundCenter(box.right, box.bottom), rotAroundCenter(box.left, box.bottom)
        )
    }

    /** التقاط نقطة على طبقة (بإحداثيات المستند). */
    fun hitTest(layer: EditLayer, docX: Float, docY: Float, docW: Float, docH: Float, slop: Float): Boolean {
        if (!layer.visible) return false
        when (layer) {
            is EditLayer.Stroke -> {
                if (layer.points.isEmpty()) return false
                val threshold = layer.widthPx / 2f + slop
                var prev = layer.points[0]
                if (layer.points.size == 1)
                    return hypot(docX - prev.first, docY - prev.second) <= threshold
                for (i in 1 until layer.points.size) {
                    val cur = layer.points[i]
                    if (distanceToSegment(docX, docY, prev, cur) <= threshold) return true
                    prev = cur
                }
                return false
            }
            is EditLayer.Shape -> if (layer.kind.isLinear) {
                val rad = Math.toRadians(layer.rotation.toDouble())
                val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
                fun rot(x: Float, y: Float): Pair<Float, Float> {
                    val dx = x - layer.centerX; val dy = y - layer.centerY
                    return Pair(layer.centerX + dx * c - dy * s, layer.centerY + dx * s + dy * c)
                }
                return distanceToSegment(
                    docX, docY, rot(layer.x1, layer.y1), rot(layer.x2, layer.y2)
                ) <= layer.strokeWidthPx / 2f + slop
            }
            else -> {}
        }
        val corners = layerDocCorners(layer, docW, docH)
        if (corners.size != 4) return false
        return pointInQuad(docX, docY, corners)
    }

    private fun pointInQuad(x: Float, y: Float, quad: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val xi = quad[i].first; val yi = quad[i].second
            val xj = quad[j].first; val yj = quad[j].second
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun distanceToSegment(
        px: Float, py: Float, a: Pair<Float, Float>, b: Pair<Float, Float>
    ): Float {
        val dx = b.first - a.first; val dy = b.second - a.second
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - a.first, py - a.second)
        var t = ((px - a.first) * dx + (py - a.second) * dy) / len2
        t = t.coerceIn(0f, 1f)
        return hypot(px - (a.first + t * dx), py - (a.second + t * dy))
    }
}
