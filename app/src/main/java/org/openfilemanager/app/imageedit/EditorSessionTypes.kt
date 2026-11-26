package org.openfilemanager.app.imageedit

import org.openfilemanager.app.imageedit.engine.ImageRenderer
import org.openfilemanager.app.imageedit.model.BrushStyle
import org.openfilemanager.app.imageedit.model.CUT_EDGE_DEFAULT
import org.openfilemanager.app.imageedit.model.CUT_TOLERANCE_DEFAULT
import org.openfilemanager.app.imageedit.model.CutMode
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.EditorTool
import org.openfilemanager.app.imageedit.model.isInteractiveWith
import org.openfilemanager.app.imageedit.model.ShapeKind
import org.openfilemanager.core.imageedit.Adjust
import org.openfilemanager.core.imageedit.CutShape
import org.openfilemanager.core.imageedit.ImageTransform

/** أنواع جلسة التحرير المستخرجة من ImageEditorViewModel.kt (N5) — لا تغيير في السلوك. */

data class EditorUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val name: String = "",
    val docW: Float = 1f,
    val docH: Float = 1f,
    /** مقياس المعاينة: بكسل معاينة لكل وحدة مستند (< 1). */
    val previewScale: Float = 1f,
    val transform: ImageTransform = ImageTransform(),
    /** تعديلات الصورة الأساس (الإضاءة/الألوان/الفلاتر/التظليل الحوافي). */
    val adjust: Adjust = Adjust(),
    val layers: List<EditLayer> = emptyList(),
    val selectedLayerId: Long? = null,
    val tool: EditorTool = EditorTool.NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    // إعدادات الأدوات (خارج التاريخ)
    val drawColor: Long = 0xFF00E476,
    val drawWidthScreenPx: Float = 8f,
    val drawStyle: BrushStyle = BrushStyle.PEN,
    val drawEraser: Boolean = false,
    val shapeKind: ShapeKind = ShapeKind.RECT,
    val shapeStrokeColor: Long = 0xFF00E476,
    val shapeFillColor: Long? = null,
    val shapeWidthScreenPx: Float = 8f,
    // القص الحر وقص الخلفية
    val cutOps: List<CutOp> = emptyList(),
    val cutMode: CutMode = CutMode.KEEP,
    /** شكل حدود القصّ في وضعي الإبقاء والمسح: حر أو مربع/دائرة... */
    val cutShape: CutShape = CutShape.FREE,
    val cutTolerance: Int = CUT_TOLERANCE_DEFAULT,
    /** إحكام الحواف 0..100: يمنع نزيف قصّ الخلفية عبر الحواف الناعمة. */
    val cutEdge: Int = CUT_EDGE_DEFAULT,
    /** تنعيم حافة القصّ 0..100 (0 = حادة). */
    val cutFeather: Int = 0,
    /** سماكة فرش الممحاة/الاستعادة/الموزاييك على الشاشة. */
    val cutBrushScreenPx: Float = 36f,
    /** صلابة الفرش 100 = حافة حادة. */
    val cutBrushHardness: Int = 100
) {
    val selected: EditLayer? get() = layers.firstOrNull { it.id == selectedLayerId }
}

/** عنصر قصّ حي قيد الرسم على القماش (يُعرض كتراكب ولا يدخل التاريخ حتى يُثبَّت). */
data class LiveCut(
    val mode: CutMode,
    val points: List<Pair<Float, Float>>,
    val brushScreenPx: Float = 0f
)

sealed interface EditorEvent {
    data class Message(val res: Int, val arg: String? = null) : EditorEvent
    data class Saved(val path: String) : EditorEvent
    data class Failed(val res: Int) : EditorEvent
}

/**
 * عقد التفاعل بين قماش المحرّر وجلسة التحرير. كل الإحداثيات بوحدات المستند
 * (بكسل الصورة الأصلية) — القماش يحوّل إحداثيات الشاشة قبل النداء.
 */
interface EditorCanvasController {
    /** لمسة قصيرة: تحديد/إلغاء تحديد طبقة. */
    fun onCanvasTap(docX: Float, docY: Float)

    /** سحب الطبقة المحددة (مستمر؛ يُسجَّل في التاريخ مرة عند بداية الإيماءة). */
    fun onLayerMove(deltaDocX: Float, deltaDocY: Float)

    /** مقابض التحجيم/التدوير: الهندسة الجديدة كاملة. */
    fun onLayerGeo(centerX: Float, centerY: Float, scale: Float, rotation: Float)

    /** نقطة ضربة فرشاة (start = بداية ضربة جديدة). */
    fun onStrokePoint(docX: Float, docY: Float, start: Boolean)

    fun onStrokeFinish()

    /** نقطة ممحاة الضربات (start = بداية إيماءة الممحاة). */
    fun onEraseStrokeAt(docX: Float, docY: Float, start: Boolean)

    /** سحب إنشاء شكل (start = بداية السحب). */
    fun onShapeDrag(docX: Float, docY: Float, start: Boolean)

    fun onShapeDragFinish()

    /** بداية إيماءة تحرير (نقل/تحجيم/تدوير/قص): تُسجَّل في التاريخ مرة واحدة. */
    fun beginGesture()

    /** نهاية إيماءة التحرير: يُغلق إدخال التاريخ المفتوح في [beginGesture]. */
    fun endGesture()

    /** نقطة حدّ حر للقص (start = بداية رسم جديد). */
    fun onCutPathPoint(docX: Float, docY: Float, start: Boolean)

    /** حدود شكل قصّ (مربع/دائرة...) أثناء السحب. */
    fun onCutShapePoints(points: List<Pair<Float, Float>>)

    /** نهاية رسم الحدّ: تُثبَّت عملية القص. */
    fun onCutPathFinish()

    /** نقطة فرشاة قصّ: ممحاة/استعادة/موزاييك (start = بداية ضربة). */
    fun onCutBrushPoint(docX: Float, docY: Float, start: Boolean)

    /** نهاية ضربة فرشاة القصّ. */
    fun onCutBrushFinish()

    /** لمسة العصا السحرية (قص الخلفية). */
    fun onCutTap(docX: Float, docY: Float)

    /** إلغاء أي عنصر حي قيد الإنشاء (تبديل أداة مثلاً). */
    fun cancelLive()
}

internal fun layerHit(docX: Float, docY: Float, s: EditorUiState): Long? {
    val slop = 12f / s.previewScale // تسامح لمس بوحدات المستند
    for (layer in s.layers.asReversed()) {
        if (!layer.visible) continue
        // الطبقات المسموح لمسها تتبع الأداة الحالية، والممحاة تلتقط الضربات
        val eligible = layer.isInteractiveWith(s.tool) ||
            (s.tool == EditorTool.DRAW && layer is EditLayer.Stroke)
        if (!eligible) continue
        if (ImageRenderer.hitTest(layer, docX, docY, s.docW, s.docH, slop)) return layer.id
    }
    return null
}

internal fun EditLayer.movedBy(dx: Float, dy: Float, selectedLayerId: Long?): EditLayer = when (this) {
    is EditLayer.Text -> if (id == selectedLayerId) copy(centerX = centerX + dx, centerY = centerY + dy) else this
    is EditLayer.Image -> if (id == selectedLayerId) copy(centerX = centerX + dx, centerY = centerY + dy) else this
    is EditLayer.Stroke -> if (id == selectedLayerId)
        copy(centerX = centerX + dx, centerY = centerY + dy, points = points.map { Pair(it.first + dx, it.second + dy) }) else this
    is EditLayer.Shape -> if (id == selectedLayerId)
        copy(centerX = centerX + dx, centerY = centerY + dy, x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy) else this
}
