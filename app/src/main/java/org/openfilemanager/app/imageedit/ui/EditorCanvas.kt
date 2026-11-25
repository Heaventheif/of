package org.openfilemanager.app.imageedit.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.EditorCanvasController
import org.openfilemanager.app.imageedit.ImageEditorViewModel.EditorUiState
import org.openfilemanager.app.imageedit.ImageEditorViewModel.LiveCut
import org.openfilemanager.app.imageedit.model.CutMode
import org.openfilemanager.core.imageedit.CutShape
import org.openfilemanager.app.imageedit.engine.ImageRenderer
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.EditorTool
import org.openfilemanager.app.imageedit.model.isInteractiveWith
import org.openfilemanager.core.imageedit.Affine2
import org.openfilemanager.core.imageedit.CropMath
import org.openfilemanager.core.imageedit.CutShapes
import org.openfilemanager.core.imageedit.FloatRect
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * قماش المحرّر: يعرض المعاينة المرسومة (وتشمل الطبقات) مع إطار التحديد
 * ومقابض التحجيم/التدوير وواجهة القصّ، ويحوّل كل الإيماءات إلى إحداثيات
 * المستند قبل تمريرها للمتحكم — فلا تعرف الواجهة شيئاً عن دقة الشاشة.
 *
 * الإيماءات: قرصة = تكبير/تحريك؛ إصبع واحد = حسب الأداة (رسم/سحب/قصّ/تحديد)،
 * أو تحريك اللوحة عند التكبير، ولمسة قصيرة = تحديد.
 *
 * في أداة القصّ يُرسم فوق الصورة تراكب حيّ: تعتيم المنطقة التي ستُزال أثناء رسم
 * الحدّ، ومؤشّر فرشاة بمقدار سماكتها الحقيقية للممحاة/الاستعادة/الموزاييك.
 */
@Composable
fun EditorCanvas(
    state: EditorUiState,
    preview: Bitmap?,
    controller: EditorCanvasController,
    cropRect: FloatRect?,
    onCropRectChange: (FloatRect?) -> Unit,
    liveCut: LiveCut? = null
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) } // بوحدات بكسل المعاينة
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val liveState by rememberUpdatedState(state)
    val liveCrop by rememberUpdatedState(cropRect)
    val liveOnCropChange by rememberUpdatedState(onCropRectChange)

    // ------------------------------------------------------------------
    // هندسة العرض — دوال محلية تقرأ الحالة الحية

    fun geom(): Layer0Geom {
        val outW = preview?.width ?: 1
        val outH = preview?.height ?: 1
        val s = liveState
        val fit = min(viewSize.width.toFloat() / outW, viewSize.height.toFloat() / outH)
            .coerceAtLeast(0.01f)
        return Layer0Geom(outW, outH, s, zoom, pan, viewSize, fit)
    }

    val drawPreview = preview

    fun setZoom(target: Float) {
        val z = target.coerceIn(1f, 6f)
        if (z == zoom) return
        zoom = z
        // مركز الشاشة يبقى ثابتاً؛ الإزاحة تُقيد داخل حدود اللوحة الجديدة
        pan = geom().clampPan(pan)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val s = liveState
                    val g0 = geom()

                    var mode = ModePanOrTap
                    var handleHit = -1

                    when (s.tool) {
                        EditorTool.DRAW -> mode = if (s.drawEraser) ModeErase else ModeDraw
                        EditorTool.CUT -> mode = when (s.cutMode) {
                            // العصا: لمسة تمسح، والسحب يحرّك اللوحة عند التكبير
                            CutMode.WAND -> ModePanOrTap
                            CutMode.KEEP, CutMode.ERASE ->
                                if (s.cutShape == CutShape.FREE) ModeLasso else ModeCutShape
                            // الممحاة/الاستعادة/الموزاييك: ضربات فرشاة
                            else -> ModeCutBrush
                        }
                        EditorTool.CROP -> {
                            val rect = liveCrop ?: g0.fullPreCropRect()
                            val corners = g0.cropCornersScreen(rect)
                            handleHit = corners.indexOfFirst { (it - down.position).getDistance() < 48f }
                            mode = when {
                                handleHit >= 0 -> ModeCropCorner
                                g0.pointInCrop(rect, down.position) -> ModeCropMove
                                else -> ModeCropNew
                            }
                        }
                        else -> {
                            // (تصحيح) التحديد والتحريك يخصان طبقات الأداة الحالية فقط
                            val sel = s.selected?.takeIf { it.isInteractiveWith(s.tool) }
                            if (sel != null && sel !is EditLayer.Stroke) {
                                val handles = g0.layerHandles(sel)
                                if ((handles.second - down.position).getDistance() < 48f) {
                                    mode = ModeRotate
                                } else {
                                    handleHit = handles.first.indexOfFirst { (it - down.position).getDistance() < 48f }
                                    if (handleHit >= 0) mode = ModeHandle
                                }
                            }
                            if (mode == ModePanOrTap) {
                                val doc = g0.screenToDoc(down.position)
                                if (s.tool == EditorTool.SHAPES) {
                                    // في أداة الأشكال: السحب فوق الشكل المحدد يحرّكه، وأي سحب
                                    // آخر يرسم شكلاً جديداً (حتى فوق صورة تغطي اللوحة)
                                    val hitSel = sel != null && ImageRenderer.hitTest(
                                        sel, doc.x, doc.y, s.docW, s.docH, 12f / s.previewScale
                                    )
                                    mode = if (hitSel) ModeMoveLayer else ModeShapeDraw
                                } else if (layerHitDoc(s, doc)) {
                                    mode = ModeMoveLayer
                                    controller.onCanvasTap(doc.x, doc.y) // تحديد فوري عند بدء السحب
                                }
                            }
                        }
                    }

                    var moved = false
                    var gestureStarted = false
                    var lastPos = down.position
                    var prevCentroid: Offset? = null
                    var prevSpread = 0f
                    var centerScreen = Offset.Zero
                    var startRadius = 1f
                    var startAngle = 0f
                    var startRotation = 0f
                    val cropAnchor = g0.screenToPreCropDoc(down.position)
                    var cropStartRect = liveCrop ?: g0.fullPreCropRect()
                    var strokeStarted = false
                    var eraseStarted = false
                    var shapeStarted = false
                    var lassoStarted = false
                    var brushStarted = false
                    var cropStarted = false

                    fun beginOnce() {
                        if (!gestureStarted) { gestureStarted = true; controller.beginGesture() }
                    }

                    // (تصحيح F15-استعادة) إغلاق الوضع النشط أينما انتهى: هبوط إصبع ثانٍ
                    // كان يبدّل mode إلى ModePinch نهائياً فلا يُستدعى
                    // onStrokeFinish/onShapeDragFinish/endGesture — الضربة
                    // تُهمل، وendGesture المفقود يترك gestureRecording عالقاً
                    // فيتعطل التاريخ (التراجع) كله حتى إعادة فتح المحرّر.
                    fun finishActiveMode() {
                        when (mode) {
                            ModeDraw -> if (strokeStarted) controller.onStrokeFinish()
                            ModeLasso -> if (lassoStarted) controller.onCutPathFinish()
                            ModeCutShape -> if (shapeStarted) controller.onCutPathFinish()
                            ModeCutBrush -> if (brushStarted) controller.onCutBrushFinish()
                            // لمسة بلا سحب في أداة الأشكال: لا تُثبَّت شكلاً منعدماً (cancelLive من الدفعة الثانية)
                            ModeShapeDraw -> if (shapeStarted) {
                                if (moved) controller.onShapeDragFinish() else controller.cancelLive()
                            }
                            ModeMoveLayer, ModeHandle, ModeRotate ->
                                if (gestureStarted) controller.endGesture()
                            ModeCropCorner, ModeCropMove, ModeCropNew ->
                                if (cropStarted) controller.endGesture()
                            else -> {}
                        }
                        strokeStarted = false
                        lassoStarted = false
                        brushStarted = false
                        shapeStarted = false
                        gestureStarted = false
                        cropStarted = false
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // (تصحيح F15-استعادة) إن كان وضع تحرير جارياً فأغلقه أولاً
                            // (تسجيل الضربة/الشكل/إغلاق إدخال التاريخ) ثم قرصة.
                            if (mode != ModePinch) {
                                finishActiveMode()
                                mode = ModePinch
                            }
                            val c = (pressed[0].position + pressed[1].position) / 2f
                            val spread = (pressed[0].position - pressed[1].position).getDistance()
                            if (prevSpread > 0f && spread > 0f) {
                                val factor = (spread / prevSpread).coerceIn(0.6f, 1.7f)
                                zoom = (zoom * factor).coerceIn(1f, 6f)
                                val g = geom()
                                val d = g.screenDeltaToOut(c - (prevCentroid ?: c))
                                pan = g.clampPan(pan + d)
                            }
                            prevCentroid = c
                            prevSpread = spread
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                            ?: break
                        val released = !change.pressed
                        // (تحسين) رفع آخر إصبع يُعالَج كآخر نقطة لا يُهمل: لمسة بلا سحب في وضع
                        // الرسم/الفرشاة ترسم نقطة، وتُغلق الأوضاع المستمرة على موضع الرفع.
                        // وضع القرصة وحده ينتهي فور رفع آخر إصبع (لا نقطة له عند الرفع).
                        if (released && pressed.isEmpty() && mode == ModePinch) break

                        val pos = change.position
                        val delta = pos - lastPos
                        if ((pos - down.position).getDistance() > touchSlop) moved = true
                        lastPos = pos

                        val continuous = mode == ModeDraw || mode == ModeErase || mode == ModeLasso ||
                            mode == ModeShapeDraw || mode == ModeCropNew || mode == ModePinch ||
                            mode == ModeCutShape || mode == ModeCutBrush
                        if (!moved && !continuous) {
                            change.consume()
                            if (released) break else continue
                        }

                        val g = geom()
                        when (mode) {
                            ModeDraw -> {
                                val start = !strokeStarted
                                strokeStarted = true
                                if (start) gestureStarted = true
                                val doc = g.screenToDoc(pos)
                                controller.onStrokePoint(doc.x, doc.y, start)
                            }
                            ModeLasso -> {
                                val start = !lassoStarted
                                lassoStarted = true
                                val doc = g.screenToDoc(pos)
                                controller.onCutPathPoint(doc.x, doc.y, start)
                            }
                            ModeCutShape -> {
                                val start = !shapeStarted
                                shapeStarted = true
                                if (start) gestureStarted = true
                                // الشكل من نقطة البدء إلى موضع الإصبع (الإحداثيات تمرّ بمصفوفة
                                // العرض فتدور وتنقلب مع الصورة)
                                val a = g.screenToDoc(down.position)
                                val b = g.screenToDoc(pos)
                                controller.onCutShapePoints(CutShapes.polygon(s.cutShape, a.x, a.y, b.x, b.y))
                            }
                            ModeCutBrush -> {
                                val start = !brushStarted
                                brushStarted = true
                                val doc = g.screenToDoc(pos)
                                controller.onCutBrushPoint(doc.x, doc.y, start)
                            }
                            ModeErase -> {
                                val start = !eraseStarted
                                eraseStarted = true
                                val doc = g.screenToDoc(pos)
                                controller.onEraseStrokeAt(doc.x, doc.y, start)
                            }
                            ModeMoveLayer -> {
                                beginOnce()
                                val d = g.screenDeltaToDoc(delta)
                                controller.onLayerMove(d.x, d.y)
                            }
                            ModeHandle, ModeRotate -> {
                                val sel = s.selected
                                if (sel != null) {
                                    if (!gestureStarted) {
                                        beginOnce()
                                        centerScreen = g.docToScreen(sel.centerX, sel.centerY)
                                        startRadius = (down.position - centerScreen).getDistance().coerceAtLeast(8f)
                                        startAngle = atan2(
                                            down.position.y - centerScreen.y,
                                            down.position.x - centerScreen.x
                                        )
                                        startRotation = sel.rotation
                                    }
                                    val r = (pos - centerScreen).getDistance().coerceAtLeast(1f)
                                    val ang = atan2(pos.y - centerScreen.y, pos.x - centerScreen.x)
                                    val scale = if (mode == ModeRotate) 1f else r / startRadius
                                    val rotation = startRotation + Math.toDegrees((ang - startAngle).toDouble()).toFloat()
                                    controller.onLayerGeo(sel.centerX, sel.centerY, scale, rotation)
                                }
                            }
                            ModeShapeDraw -> {
                                val start = !shapeStarted
                                shapeStarted = true
                                if (start) gestureStarted = true
                                val doc = g.screenToDoc(pos)
                                controller.onShapeDrag(doc.x, doc.y, start)
                            }
                            ModeCropCorner -> {
                                if (!cropStarted) { cropStarted = true; gestureStarted = true; cropStartRect = liveCrop ?: g.fullPreCropRect() }
                                val doc = g.screenToPreCropDoc(pos)
                                liveOnCropChange(moveCropCorner(cropStartRect, handleHit, doc, g.fullPreCropRect()))
                            }
                            ModeCropMove -> {
                                if (!cropStarted) { cropStarted = true; gestureStarted = true; cropStartRect = liveCrop ?: g.fullPreCropRect() }
                                val d = g.screenDeltaToPreCropDoc(delta)
                                val moved_ = cropStartRect.translate(d.x, d.y)
                                liveOnCropChange(moved_.clampedKeepingSize(g.fullPreCropRect().width, g.fullPreCropRect().height))
                            }
                            ModeCropNew -> {
                                if (!cropStarted) { cropStarted = true; gestureStarted = true }
                                val doc = g.screenToPreCropDoc(pos)
                                liveOnCropChange(
                                    FloatRect(
                                        minOf(cropAnchor.x, doc.x), minOf(cropAnchor.y, doc.y),
                                        maxOf(cropAnchor.x, doc.x), maxOf(cropAnchor.y, doc.y)
                                    ).coerceIn(g.fullPreCropRect().width, g.fullPreCropRect().height)
                                )
                            }
                            ModePanOrTap -> if (zoom > 1f) pan = g.clampPan(pan + g.screenDeltaToOut(delta))
                        }
                        change.consume()
                        if (released) break
                    }

                    // ---- نهاية الإيماءة (تصحيح F15-استعادة: نفس مسار الإغلاق الموحد)
                    finishActiveMode()
                    val isTap = !moved && mode != ModePinch && mode != ModeDraw && mode != ModeErase && mode != ModeLasso &&
                        mode != ModeShapeDraw && !cropStarted
                    if (mode == ModeShapeDraw && !moved) {
                        val doc = geom().screenToDoc(down.position)
                        controller.onCanvasTap(doc.x, doc.y)
                    }
                    if (isTap && mode == ModePanOrTap) {
                        val g = geom()
                        val doc = g.screenToDoc(down.position)
                        if (s.tool == EditorTool.CUT) controller.onCutTap(doc.x, doc.y)
                        else controller.onCanvasTap(doc.x, doc.y)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawEditorFrame(drawPreview, liveState, cropRect, liveCut, ::geom)
        }
        // (تحسين) التحكم بالتكبير: كان التكبير بالقرصة بلا طريقة للعودة إلى الملاءمة
        ZoomControls(
            zoom = zoom,
            onZoom = { setZoom(it) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        )
    }

}

/** أزرار تصغير/ملاءمة/تكبير مع نسبة التكبير الحالية؛ تظهر فوق القماش في وسطه العلوي. */
@Composable
private fun ZoomControls(zoom: Float, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(onClick = { onZoom(zoom / 1.5f) }, enabled = zoom > 1.01f, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Remove, stringResource(R.string.ie_zoom_out), Modifier.size(18.dp))
        }
        Surface(
            onClick = { onZoom(1f) },
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(zoom * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                if (zoom > 1.01f) {
                    Icon(
                        Icons.Outlined.FitScreen, stringResource(R.string.ie_zoom_fit),
                        tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(16.dp)
                    )
                }
            }
        }
        FilledTonalIconButton(onClick = { onZoom(zoom * 1.5f) }, enabled = zoom < 5.99f, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Add, stringResource(R.string.ie_zoom_in), Modifier.size(18.dp))
        }
    }
}

// ---------------------------------------------------------------------
// الأوضاع

/** نسيج رقعة الشطرنج (خلية 12px) يتكرر عبر BitmapShader. */
private val checkerBrush: androidx.compose.ui.graphics.ShaderBrush by lazy {
    val cell = 12
    val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint()
    p.color = 0xFF3A3A3A.toInt(); c.drawRect(0f, 0f, cell * 2f, cell * 2f, p)
    p.color = 0xFF565656.toInt()
    c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
    c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
    androidx.compose.ui.graphics.ShaderBrush(
        android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    )
}

private const val ModePanOrTap = 0
private const val ModeDraw = 1
private const val ModeErase = 2
private const val ModeMoveLayer = 3
private const val ModeHandle = 4
private const val ModeRotate = 5
private const val ModeShapeDraw = 6
private const val ModeCropCorner = 7
private const val ModeCropMove = 8
private const val ModeCropNew = 9
private const val ModePinch = 11
private const val ModeLasso = 12
private const val ModeCutShape = 13
private const val ModeCutBrush = 14

// ---------------------------------------------------------------------
// هندسة العرض: صنف خالص يبنى عند الحاجة من الحالة الحية

private class Layer0Geom(
    val outW: Int,
    val outH: Int,
    val s: EditorUiState,
    val zoom: Float,
    val pan: Offset,
    val viewSize: IntSize,
    val fit: Float
) {
    val effScale = fit * zoom
    val originX = viewSize.width / 2f - effScale * (outW / 2f + pan.x)
    val originY = viewSize.height / 2f - effScale * (outH / 2f + pan.y)

    private val docToOutM: Affine2? =
        if (s.docW > 0 && s.docH > 0 && s.previewScale > 0)
            ImageRenderer.outputMatrix(s.transform, s.docW, s.docH, s.previewScale) else null

    private val preCropToOutM: Affine2 =
        s.transform.preCropMatrix(s.docW, s.docH)
            .then(Affine2.scaling(s.previewScale, s.previewScale))

    private val outToDocM: Affine2? = docToOutM?.inverted()
    private val outToPreCropM: Affine2 = preCropToOutM.inverted() ?: Affine2.IDENTITY

    fun screenToOut(p: Offset): Offset =
        Offset((p.x - originX) / effScale, (p.y - originY) / effScale)

    fun screenDeltaToOut(d: Offset): Offset = Offset(d.x / effScale, d.y / effScale)

    fun screenToDoc(p: Offset): Offset {
        val o = screenToOut(p)
        val r = outToDocM?.apply(o.x, o.y) ?: return Offset.Zero
        return Offset(r.first, r.second)
    }

    fun screenDeltaToDoc(d: Offset): Offset {
        val o = screenDeltaToOut(d)
        val r = outToDocM?.applyVector(o.x, o.y) ?: return Offset.Zero
        return Offset(r.first, r.second)
    }

    fun screenToPreCropDoc(p: Offset): Offset {
        val o = screenToOut(p)
        val r = outToPreCropM.apply(o.x, o.y)
        return Offset(r.first, r.second)
    }

    fun screenDeltaToPreCropDoc(d: Offset): Offset {
        val o = screenDeltaToOut(d)
        val r = outToPreCropM.applyVector(o.x, o.y)
        return Offset(r.first, r.second)
    }

    fun docToScreen(x: Float, y: Float): Offset {
        val r = docToOutM?.apply(x, y) ?: return Offset.Zero
        return Offset(originX + r.first * effScale, originY + r.second * effScale)
    }

    fun preCropDocToScreen(x: Float, y: Float): Offset {
        val r = preCropToOutM.apply(x, y)
        return Offset(originX + r.first * effScale, originY + r.second * effScale)
    }

    fun fullPreCropRect(): FloatRect {
        val (w, h) = s.transform.preCropSize(s.docW, s.docH)
        return FloatRect.xywh(0f, 0f, w, h)
    }

    fun cropCornersScreen(rect: FloatRect): List<Offset> = listOf(
        preCropDocToScreen(rect.left, rect.top),
        preCropDocToScreen(rect.right, rect.top),
        preCropDocToScreen(rect.right, rect.bottom),
        preCropDocToScreen(rect.left, rect.bottom)
    )

    fun pointInCrop(rect: FloatRect, p: Offset): Boolean {
        val cs = cropCornersScreen(rect)
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val yi = cs[i].y; val yj = cs[j].y
            if ((yi > p.y) != (yj > p.y) &&
                p.x < (cs[j].x - cs[i].x) * (p.y - yi) / (yj - yi) + cs[i].x
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** زوايا صندوق التحديد ومقبض التدوير على الشاشة. */
    fun layerHandles(sel: EditLayer): Pair<List<Offset>, Offset> {
        val corners = ImageRenderer.layerDocCorners(sel, s.docW, s.docH)
            .map { docToScreen(it.first, it.second) }
        if (corners.size != 4) return Pair(emptyList(), Offset.Zero)
        val topMid = Offset((corners[0].x + corners[1].x) / 2f, (corners[0].y + corners[1].y) / 2f)
        val center = Offset((corners[0].x + corners[2].x) / 2f, (corners[0].y + corners[2].y) / 2f)
        val dir = topMid - center
        val len = hypot(dir.x, dir.y).coerceAtLeast(1f)
        val rotateHandle = topMid + Offset(dir.x / len, dir.y / len) * 56f
        return Pair(corners, rotateHandle)
    }

    fun clampPan(p: Offset): Offset {
        val maxX = outW * (zoom - 1f) / 2f + 64f
        val maxY = outH * (zoom - 1f) / 2f + 64f
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
}

private fun moveCropCorner(base: FloatRect, corner: Int, doc: Offset, bounds: FloatRect): FloatRect {
    var l = base.left; var t = base.top; var r = base.right; var b = base.bottom
    when (corner) {
        0 -> { l = doc.x; t = doc.y }
        1 -> { r = doc.x; t = doc.y }
        2 -> { r = doc.x; b = doc.y }
        3 -> { l = doc.x; b = doc.y }
    }
    if (r - l < CropMath.MIN_SIZE || b - t < CropMath.MIN_SIZE) return base
    return FloatRect(l, t, r, b).coerceIn(bounds.width, bounds.height)
}

private fun FloatRect.translate(dx: Float, dy: Float): FloatRect =
    FloatRect(left + dx, top + dy, right + dx, bottom + dy)

private fun layerHitDoc(s: EditorUiState, doc: Offset): Boolean {
    val slop = 12f / s.previewScale
    for (layer in s.layers.asReversed()) {
        if (!layer.visible || !layer.isInteractiveWith(s.tool)) continue
        if (ImageRenderer.hitTest(layer, doc.x, doc.y, s.docW, s.docH, slop)) return true
    }
    return false
}

// ---------------------------------------------------------------------
// الرسم

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditorFrame(
    preview: Bitmap?,
    state: EditorUiState,
    cropRect: FloatRect?,
    liveCut: LiveCut?,
    geom: () -> Layer0Geom
) {
    val bmp = preview ?: return
    val g = geom()
    // رقعة شطرنج خلف الصورة لتظهر المناطق الشفافة بعد القص
    drawRect(
        brush = checkerBrush,
        topLeft = Offset(g.originX, g.originY),
        size = Size(g.outW * g.effScale, g.outH * g.effScale)
    )
    drawImage(
        image = bmp.asImageBitmap(),
        dstOffset = IntOffset(g.originX.roundToInt(), g.originY.roundToInt()),
        dstSize = IntSize(
            (g.outW * g.effScale).roundToInt().coerceAtLeast(1),
            (g.outH * g.effScale).roundToInt().coerceAtLeast(1)
        )
    )

    // تراكب القصّ الحي: ما سيُزال معتَم، وخط الحدّ، ومؤشّر الفرشاة
    if (liveCut != null) drawLiveCutOverlay(g, liveCut)

    // إطار التحديد ومقابضه
    if (state.tool != EditorTool.CROP && state.tool != EditorTool.DRAW && state.tool != EditorTool.CUT) {
        val sel = state.selected?.takeIf { it.isInteractiveWith(state.tool) }
        if (sel != null && sel !is EditLayer.Stroke) {
            val (corners, rotHandle) = g.layerHandles(sel)
            if (corners.size == 4) {
                val path = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    for (i in 1 until 4) lineTo(corners[i].x, corners[i].y)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = 0.95f), style = Stroke(2f))
                for (c in corners) {
                    drawCircle(Color.Black.copy(alpha = 0.55f), radius = 14f, center = c)
                    drawCircle(Color.White, radius = 11f, center = c)
                }
                val topMid = Offset((corners[0].x + corners[1].x) / 2f, (corners[0].y + corners[1].y) / 2f)
                drawLine(Color.White, topMid, rotHandle, strokeWidth = 2f)
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = 14f, center = rotHandle)
                drawCircle(Color.White, radius = 11f, center = rotHandle)
            }
        }
    }

    // واجهة القصّ
    if (state.tool == EditorTool.CROP) {
        val rect = cropRect ?: g.fullPreCropRect()
        val c = g.cropCornersScreen(rect)
        val minX = c.minOf { it.x }.coerceIn(0f, size.width)
        val maxX = c.maxOf { it.x }.coerceIn(0f, size.width)
        val minY = c.minOf { it.y }.coerceIn(0f, size.height)
        val maxY = c.maxOf { it.y }.coerceIn(0f, size.height)
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, minY))
        drawRect(scrim, topLeft = Offset(0f, maxY), size = Size(size.width, size.height - maxY))
        drawRect(scrim, topLeft = Offset(0f, minY), size = Size(minX, maxY - minY))
        drawRect(scrim, topLeft = Offset(maxX, minY), size = Size((size.width - maxX).coerceAtLeast(0f), maxY - minY))

        val border = Path().apply {
            moveTo(c[0].x, c[0].y)
            for (i in 1 until 4) lineTo(c[i].x, c[i].y)
            close()
        }
        drawPath(border, Color.White, style = Stroke(2.5f))
        for (i in 1..2) {
            val f1 = Offset(c[0].x + (c[1].x - c[0].x) * i / 3f, c[0].y + (c[1].y - c[0].y) * i / 3f)
            val f2 = Offset(c[3].x + (c[2].x - c[3].x) * i / 3f, c[3].y + (c[2].y - c[3].y) * i / 3f)
            drawLine(Color.White.copy(alpha = 0.6f), f1, f2, strokeWidth = 1.2f)
            val h1 = Offset(c[0].x + (c[3].x - c[0].x) * i / 3f, c[0].y + (c[3].y - c[0].y) * i / 3f)
            val h2 = Offset(c[1].x + (c[2].x - c[1].x) * i / 3f, c[1].y + (c[2].y - c[1].y) * i / 3f)
            drawLine(Color.White.copy(alpha = 0.6f), h1, h2, strokeWidth = 1.2f)
        }
        for (p in c) {
            drawCircle(Color.White, radius = 14f, center = p)
            drawCircle(Color.Black, radius = 11f, center = p)
        }
    }
}

// ---------------------------------------------------------------------
// تراكب القصّ الحي

private val CutKeepColor = Color(0xFF00E5FF)
private val CutEraseColor = Color(0xFFFF5252)
private val CutRestoreColor = Color(0xFF00E676)
private val CutMosaicColor = Color(0xFFFFB300)

/**
 * يعرض على القماش ما سيحدث قبل تثبيته: في «قص حر» يُعتَم خارج الحدّ المرسوم، وفي
 * «مسح منطقة» يُظلَّل داخلَه، وفرش القصّ تُظهر خطّها ومقدار سماكتها الفعلي.
 * الرسم مقصوص على مستطيل الصورة كي لا يتجاوز التعتيم إلى خلفية القماش.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiveCutOverlay(
    g: Layer0Geom,
    live: LiveCut
) {
    val left = g.originX
    val top = g.originY
    val right = g.originX + g.outW * g.effScale
    val bottom = g.originY + g.outH * g.effScale
    if (live.points.isEmpty()) return
    clipRect(left, top, right, bottom) {
        when (live.mode) {
            CutMode.ERASER, CutMode.RESTORE, CutMode.MOSAIC -> {
                val color = when (live.mode) {
                    CutMode.ERASER -> CutEraseColor
                    CutMode.RESTORE -> CutRestoreColor
                    else -> CutMosaicColor
                }
                val screen = live.points.map { g.docToScreen(it.first, it.second) }
                val radius = live.brushScreenPx / 2f
                if (screen.size >= 2) {
                    val path = Path().apply {
                        moveTo(screen[0].x, screen[0].y)
                        for (i in 1 until screen.size) lineTo(screen[i].x, screen[i].y)
                    }
                    drawPath(path, color.copy(alpha = 0.35f), style = Stroke(radius))
                }
                // مؤشّر الفرشاة عند آخر موضع: دائرة بمقدار السماكة الحقيقية
                val head = screen.last()
                drawCircle(color.copy(alpha = 0.18f), radius = radius, center = head)
                drawCircle(color, radius = radius, center = head, style = Stroke(1.5f))
                drawCircle(color, radius = 3f, center = head)
            }
            CutMode.KEEP, CutMode.ERASE -> {
                if (live.points.size < 2) return@clipRect
                val screen = live.points.map { g.docToScreen(it.first, it.second) }
                val path = Path().apply {
                    moveTo(screen[0].x, screen[0].y)
                    for (i in 1 until screen.size) lineTo(screen[i].x, screen[i].y)
                    if (screen.size >= 3) close()
                }
                val accent = if (live.mode == CutMode.KEEP) CutKeepColor else CutEraseColor
                if (live.mode == CutMode.KEEP) {
                    if (screen.size >= 3) {
                        // الخارج هو ما سيُزال: تعتيم على كامل الصورة عدا داخل الحدّ
                        val outside = Path().apply {
                            addRect(Rect(Offset.Zero, size))
                            addPath(path)
                            fillType = PathFillType.EvenOdd
                        }
                        drawPath(outside, Color.Black.copy(alpha = 0.62f))
                    }
                } else {
                    drawPath(path, CutEraseColor.copy(alpha = 0.45f))
                }
                // خط الحدّ: هالة داكنة تحته ليظهر فوق الألوان الفاتحة والداكنة
                drawPath(path, Color.Black.copy(alpha = 0.55f), style = Stroke(5f))
                drawPath(path, accent, style = Stroke(2.5f))
                // نقطة البداية: حلقة يُغلق عليها الحدّ
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = 9f, center = screen[0])
                drawCircle(accent, radius = 6.5f, center = screen[0])
                drawCircle(Color.White, radius = 2.5f, center = screen[0])
                // رأس الإصبع
                val head = screen.last()
                drawCircle(Color.White, radius = 5f, center = head)
            }
            CutMode.WAND -> Unit
        }
    }
}
