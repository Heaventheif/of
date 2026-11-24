package org.openfilemanager.app.imageedit

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.engine.CutEngine
import org.openfilemanager.app.imageedit.engine.ImageDecoder
import org.openfilemanager.app.imageedit.engine.ImageExporter
import org.openfilemanager.app.imageedit.engine.ImageRenderer
import org.openfilemanager.app.imageedit.model.CutMode
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.EditorTool
import org.openfilemanager.app.imageedit.model.ExportFormat
import org.openfilemanager.app.imageedit.model.ShapeKind
import org.openfilemanager.app.imageedit.model.TextAlignment
import org.openfilemanager.app.imageedit.model.isInteractiveWith
import org.openfilemanager.core.imageedit.EditHistory
import org.openfilemanager.core.imageedit.FloatRect
import org.openfilemanager.core.imageedit.ImageTransform
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong

/**
 * جسر الواجهة بمحرّك الصور: يملك جلسة التحرير (الحالة + التاريخ + المعاينة)
 * ولا تلمس الواجهة فك الترميز أو الرسم مباشرة. كل التعديلات حالة/عمليات لا
 * تعديل للصورة الأصلية، والأصل لا يُفك قراره الكامل إلا عند التصدير.
 */
class ImageEditorViewModel(app: Application, val imagePath: String) : AndroidViewModel(app),
    EditorCanvasController {

    data class EditorUiState(
        val loading: Boolean = true,
        val failed: Boolean = false,
        val name: String = "",
        val docW: Float = 1f,
        val docH: Float = 1f,
        /** مقياس المعاينة: بكسل معاينة لكل وحدة مستند (< 1). */
        val previewScale: Float = 1f,
        val transform: ImageTransform = ImageTransform(),
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
        val drawEraser: Boolean = false,
        val shapeKind: ShapeKind = ShapeKind.RECT,
        val shapeStrokeColor: Long = 0xFF00E476,
        val shapeFillColor: Long? = null,
        val shapeWidthScreenPx: Float = 8f,
        // القص الحر وقص الخلفية
        val cutOps: List<CutOp> = emptyList(),
        val cutMode: CutMode = CutMode.KEEP,
        val cutTolerance: Int = 30
    ) {
        val selected: EditLayer? get() = layers.firstOrNull { it.id == selectedLayerId }
    }

    sealed interface EditorEvent {
        data class Message(val res: Int, val arg: String? = null) : EditorEvent
        data class Saved(val path: String) : EditorEvent
        data class Failed(val res: Int) : EditorEvent
    }

    private data class Snapshot(
        val transform: ImageTransform,
        val layers: List<EditLayer>,
        val cutOps: List<CutOp> = emptyList()
    )

    private val _state = MutableStateFlow(EditorUiState(name = imagePath.substringAfterLast('/')))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private var basePreview: Bitmap? = null
    private var exifOrientation = 1
    private val history = EditHistory<Snapshot>()
    private var initialSnap: Snapshot = Snapshot(ImageTransform(), emptyList())
    private var renderJob: Job? = null
    /** (تصحيح) إشارة إعادة رسم مدموجة: طلبات كثيرة متلاحقة = رسم واحد بأحدث حالة. */
    private val renderSignal = Channel<Unit>(Channel.CONFLATED)
    private val idGen = AtomicLong(1)
    private var gestureRecording = false
    private var sliderRecording = false
    private var gestureLayer: EditLayer? = null
    private var eraseRecorded = false
    private var lastTextRecordAt = 0L

    init {
        viewModelScope.launch(AppDispatchers.io) { load() }
        startRenderLoop()
    }

    // ------------------------------------------------------------------
    // التحميل والعرض

    private suspend fun load() {
        try {
            val path = Paths.get(imagePath)
            val bounds = ImageDecoder.readBounds(path)
            if (bounds == null) {
                _state.value = _state.value.copy(loading = false, failed = true)
                return
            }
            exifOrientation = bounds.exifOrientation
            val decoded = ImageDecoder.decodePreview(path, bounds.exifOrientation, 2048)
            if (decoded == null) {
                _state.value = _state.value.copy(loading = false, failed = true)
                return
            }
            basePreview = decoded
            val scale = decoded.width.toFloat() / bounds.width
            initialSnap = Snapshot(ImageTransform(), emptyList())
            _state.value = _state.value.copy(
                loading = false,
                name = path.fileName?.toString() ?: imagePath.substringAfterLast('/'),
                docW = bounds.width.toFloat(),
                docH = bounds.height.toFloat(),
                previewScale = scale,
                transform = ImageTransform(),
                layers = emptyList()
            )
            requestRender()
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, failed = true)
        }
    }

    /**
     * (تصحيح) حلقة الرسم. الشيفرة القديمة كانت تؤخّر كل طلب 24 مللي وتلغي السابق،
     * فأثناء السحب المتواصل (فرشاة، تحريك صورة، تدوير) يصل حدث جديد كل بضعة
     * مللي فيُلغى الرسم دائماً ولا يظهر شيء حتى يتوقف الإصبع — وهذا سبب تأخر ظهور
     * الرسم وانفصال الصورة عن إطار التحكم. وكان الرسم المُلغى يعمل أصلاً على خيوط
     * متوازية وقد ينشر نتيجة قديمة بعد الأحدث.
     *
     * الآن عامل واحد متسلسل: يرسم أحدث حالة فور فراغه، والطلبات أثناء الرسم تُدمج
     * في طلب واحد — لا تجويع ولا تراجع إلى نتيجة قديمة.
     */
    private fun startRenderLoop() {
        // (تصحيح M4) نحتفظ بمهمة الحلقة كي يصحّ إلغاؤها فعلاً عند onCleared
        // (كان renderJob لا يُسند شيئاً فيلغى الإلغاء لا شيء).
        renderJob = viewModelScope.launch(AppDispatchers.compute.limitedParallelism(1)) {
            for (signal in renderSignal) {
                val base = basePreview ?: continue
                val s = _state.value
                // أثناء أداة القصّ تُعرض اللوحة الكاملة قبل القصّ لتظهر المنطقة في سياقها
                val t = if (s.tool == EditorTool.CROP) s.transform.withCrop(null) else s.transform
                val extras = buildList {
                    liveStroke?.let { add(it) }
                    liveShape?.let { add(it) }
                }
                // (تصحيح M4) فشل الرسم لا يُهمل صامتاً: محاولة إضافية ثم إبقاء آخر معاينة ناجحة
                val renderBase = runCatching { cutBaseFor(base, s) }.getOrDefault(base)
                var rendered = runCatching {
                    ImageRenderer.render(renderBase, t, s.layers + extras, s.previewScale, opaqueBackground = false)
                }.getOrNull()
                if (rendered == null) {
                    rendered = runCatching {
                        ImageRenderer.render(renderBase, t, s.layers + extras, s.previewScale, opaqueBackground = false)
                    }.getOrNull()
                }
                if (rendered != null) _preview.value = rendered
            }
        }
    }

    /** آخر نتيجة قص محسوبة (تُستعمل فقط من خيط الرسم المتسلسل). */
    private var cutCache: Pair<List<CutOp>, Bitmap>? = null

    /** الصورة الأساس بعد تطبيق عمليات القص؛ تُحسب تزايدياً وتُخزَّن مؤقتاً. */
    private fun cutBaseFor(base: Bitmap, s: EditorUiState): Bitmap {
        val ops = s.cutOps
        if (ops.isEmpty()) return base
        val c = cutCache
        if (c != null && c.first == ops) return c.second
        val result = if (c != null && ops.size > c.first.size && ops.subList(0, c.first.size) == c.first)
            CutEngine.apply(c.second, ops.drop(c.first.size), s.docW)
        else CutEngine.apply(base, ops, s.docW)
        cutCache = ops to result
        return result
    }

    private fun requestRender() {
        if (basePreview == null) return
        renderSignal.trySend(Unit)
    }

    override fun onCleared() {
        renderSignal.close()
        renderJob?.cancel()
        // (تصحيح IE-04/F14b-استعادة) لا recycle صريح لـ basePreview: الإلغاء تعاوني
        // والمهمة قد تكون داخل كتلة الرسم فتستخدم بت ماب معاد تدويره (انهيار
        // "Canvas: trying to use a recycled bitmap"). البت ماب على API 26+
        // يُحررها جامع النفايات فور انعدام المراجع.
        basePreview = null
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // بنية التعديل والتاريخ

    private fun snapshotOf(s: EditorUiState) = Snapshot(s.transform, s.layers, s.cutOps)

    private fun refreshFlags(s: EditorUiState): EditorUiState {
        val snap = snapshotOf(s)
        return s.copy(
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            dirty = snap != initialSnap
        )
    }

    /** تعديل يسجّل في التاريخ. */
    private fun mutate(record: Boolean = true, block: (EditorUiState) -> EditorUiState) {
        val s = _state.value
        if (record && !gestureRecording && !sliderRecording) history.record(snapshotOf(s))
        _state.value = refreshFlags(block(s))
        requestRender()
    }

    /** بداية إيماءة سحب متواصلة: تسجيل واحد للسحبة كاملة لا لكل إطار. */
    override fun beginGesture() {
        val s = _state.value
        if (!gestureRecording && !sliderRecording) history.record(snapshotOf(s))
        gestureRecording = true
        gestureLayer = s.selected
    }

    override fun endGesture() {
        gestureRecording = false
        gestureLayer = null
        _state.value = refreshFlags(_state.value)
    }

    /** بداية سحب منزلق: تسجيل مرة واحدة حتى انتهاء السحب. */
    fun beginSliderCommit() {
        if (!sliderRecording && !gestureRecording) history.record(snapshotOf(_state.value))
        sliderRecording = true
    }

    fun endSliderCommit() {
        sliderRecording = false
        _state.value = refreshFlags(_state.value)
    }

    fun undo() {
        val s = _state.value
        val snap = history.undo(snapshotOf(s)) ?: return
        applySnapshot(snap)
    }

    fun redo() {
        val s = _state.value
        val snap = history.redo(snapshotOf(s)) ?: return
        applySnapshot(snap)
    }

    private fun applySnapshot(snap: Snapshot) {
        val s = _state.value
        val selection = s.selectedLayerId?.takeIf { id -> snap.layers.any { it.id == id } }
        _state.value = refreshFlags(
            s.copy(transform = snap.transform, layers = snap.layers, cutOps = snap.cutOps, selectedLayerId = selection)
        )
        requestRender()
    }

    // ------------------------------------------------------------------
    // الأدوات

    fun selectTool(tool: EditorTool) {
        val cur = _state.value
        val newTool = if (cur.tool == tool) EditorTool.NONE else tool
        // (تصحيح) لا يبقى تحديد طبقة من نوع لا تخص الأداة الجديدة (إطارها كان يبقى فوق الأداة)
        val keep = cur.selected?.takeIf { it.isInteractiveWith(newTool) }?.id
        _state.value = cur.copy(tool = newTool, selectedLayerId = keep)
        clearLive()
    }

    fun setDrawSettings(color: Long? = null, widthScreenPx: Float? = null, eraser: Boolean? = null) {
        _state.value = _state.value.copy(
            drawColor = color ?: _state.value.drawColor,
            drawWidthScreenPx = widthScreenPx ?: _state.value.drawWidthScreenPx,
            drawEraser = eraser ?: _state.value.drawEraser
        )
    }

    fun setShapeSettings(
        kind: ShapeKind? = null, strokeColor: Long? = null, fillColor: Long? = null,
        widthScreenPx: Float? = null, clearFill: Boolean = false
    ) {
        _state.value = _state.value.copy(
            shapeKind = kind ?: _state.value.shapeKind,
            shapeStrokeColor = strokeColor ?: _state.value.shapeStrokeColor,
            shapeFillColor = if (clearFill) null else (fillColor ?: _state.value.shapeFillColor),
            shapeWidthScreenPx = widthScreenPx ?: _state.value.shapeWidthScreenPx
        )
    }

    // ---- القص الحر وقص الخلفية

    fun setCutSettings(mode: CutMode? = null, tolerance: Int? = null) {
        val cur = _state.value
        _state.value = cur.copy(cutMode = mode ?: cur.cutMode, cutTolerance = tolerance ?: cur.cutTolerance)
        if (mode != null) clearLive()
    }

    /** استعادة الصورة الأصلية بإلغاء كل عمليات القص (قابلة للتراجع). */
    fun resetCut() {
        if (_state.value.cutOps.isEmpty()) return
        mutate { it.copy(cutOps = emptyList()) }
    }

    override fun onLassoPoint(docX: Float, docY: Float, start: Boolean) {
        val s = _state.value
        if (start || liveStroke == null) {
            liveStroke = EditLayer.Stroke(
                id = -1, centerX = 0f, centerY = 0f,
                points = listOf(Pair(docX, docY)),
                color = if (s.cutMode == CutMode.ERASE) 0xFFFF5252 else 0xFF00E5FF,
                widthPx = 3f / s.previewScale
            )
        } else {
            liveStroke = liveStroke?.let { it.copy(points = it.points + Pair(docX, docY)) }
        }
        requestRender()
    }

    override fun onLassoFinish() {
        val stroke = liveStroke
        liveStroke = null
        val s = _state.value
        if (stroke == null || stroke.points.size < 3) { requestRender(); return }
        val minD = 2f / s.previewScale
        val pts = ArrayList<Pair<Float, Float>>()
        for (p in stroke.points) {
            val last = pts.lastOrNull()
            if (last == null || kotlin.math.hypot(p.first - last.first, p.second - last.second) >= minD) pts.add(p)
        }
        if (pts.size < 3) { requestRender(); return }
        val op: CutOp = if (s.cutMode == CutMode.ERASE) CutOp.Erase(pts) else CutOp.Keep(pts)
        mutate { st ->
            var t = st.transform
            // القص الحر: تُقصّ الحواف الشفافة تلقائياً إن كانت الصورة بلا تدوير/قلب/قص سابق
            val plain = t.quarterTurns % 4 == 0 && t.freeRotation == 0f && !t.flipH && !t.flipV && t.crop == null
            if (op is CutOp.Keep && plain) {
                val l = pts.minOf { it.first }.coerceIn(0f, st.docW)
                val r = pts.maxOf { it.first }.coerceIn(0f, st.docW)
                val tp = pts.minOf { it.second }.coerceIn(0f, st.docH)
                val b = pts.maxOf { it.second }.coerceIn(0f, st.docH)
                if (r - l >= 2f && b - tp >= 2f) t = t.withCrop(FloatRect(l, tp, r, b))
            }
            st.copy(cutOps = st.cutOps + op, transform = t)
        }
    }

    override fun onCutTap(docX: Float, docY: Float) {
        val s = _state.value
        if (docX < 0f || docY < 0f || docX > s.docW || docY > s.docH) return
        mutate { it.copy(cutOps = it.cutOps + CutOp.Wand(docX, docY, it.cutTolerance)) }
    }

    fun addTextLayer() {
        val s = _state.value
        mutate { st ->
            val layer = EditLayer.Text(
                id = idGen.getAndIncrement(),
                centerX = st.docW / 2f,
                centerY = st.docH / 2f,
                text = "",
                fontSizePx = (st.docW.coerceAtMost(st.docH) * 0.08f).coerceAtLeast(24f),
                color = 0xFFFFFFFF,
                alignment = TextAlignment.CENTER
            )
            st.copy(layers = st.layers + layer, selectedLayerId = layer.id, tool = EditorTool.TEXT)
        }
    }

    fun updateSelectedText(record: Boolean = true, block: (EditLayer.Text) -> EditLayer.Text) {
        // دمج نقرات الكتابة المتلاحقة: تسجيل واحد كل مهلة قصيرة لا لكل حرف
        val doRecord = if (record) {
            val now = System.currentTimeMillis()
            if (now - lastTextRecordAt > 800) { lastTextRecordAt = now; true } else false
        } else false
        mutate(doRecord) { s ->
            val sel = s.selected as? EditLayer.Text ?: return@mutate s
            s.copy(layers = s.layers.map { if (it.id == sel.id) block(it as EditLayer.Text) else it })
        }
    }

    fun addImageLayer(uriString: String) {
        viewModelScope.launch(AppDispatchers.io) {
            val bmp = ImageDecoder.decodeOverlay(getApplication(), uriString)
            if (bmp == null) {
                _events.tryEmit(EditorEvent.Message(R.string.ie_overlay_failed))
                return@launch
            }
            withContext(AppDispatchers.compute) {
                mutate { s ->
                    val layer = EditLayer.Image(
                        id = idGen.getAndIncrement(),
                        centerX = s.docW / 2f,
                        centerY = s.docH / 2f,
                        docWidth = s.docW * 0.6f,
                        bitmap = bmp
                    )
                    s.copy(layers = s.layers + layer, selectedLayerId = layer.id, tool = EditorTool.IMAGE)
                }
            }
        }
    }

    fun deleteSelectedLayer() {
        mutate { s ->
            val sel = s.selectedLayerId ?: return@mutate s
            s.copy(layers = s.layers.filterNot { it.id == sel }, selectedLayerId = null)
        }
    }

    fun reorderSelected(front: Boolean) {
        mutate { s ->
            val sel = s.selected ?: return@mutate s
            val rest = s.layers.filterNot { it.id == sel.id }
            s.copy(layers = if (front) rest + sel else listOf(sel) + rest)
        }
    }

    fun setSelectedOpacity(opacity: Float) {
        mutate(record = false) { s ->
            val sel = s.selectedLayerId ?: return@mutate s
            s.copy(layers = s.layers.map {
                when (it) {
                    is EditLayer.Text -> if (it.id == sel) it.copy(opacity = opacity) else it
                    is EditLayer.Image -> if (it.id == sel) it.copy(opacity = opacity) else it
                    is EditLayer.Stroke -> if (it.id == sel) it.copy(opacity = opacity) else it
                    is EditLayer.Shape -> if (it.id == sel) it.copy(opacity = opacity) else it
                }
            })
        }
    }

    fun flipSelectedImage(horizontal: Boolean) {
        mutate { s ->
            val sel = s.selected as? EditLayer.Image ?: return@mutate s
            s.copy(layers = s.layers.map {
                if (it.id == sel.id) {
                    val img = it as EditLayer.Image
                    if (horizontal) img.copy(flipH = !img.flipH) else img.copy(flipV = !img.flipV)
                } else it
            })
        }
    }

    fun updateSelectedShape(record: Boolean = true, block: (EditLayer.Shape) -> EditLayer.Shape) {
        mutate(record) { s ->
            val sel = s.selected as? EditLayer.Shape ?: return@mutate s
            s.copy(layers = s.layers.map { if (it.id == sel.id) block(it as EditLayer.Shape) else it })
        }
    }

    // ------------------------------------------------------------------
    // التحويل (قصّ/تدوير/قلب)

    fun rotateQuarter(delta: Int) {
        mutate { s -> s.copy(transform = s.transform.appendedQuarterTurn(s.docW, s.docH, delta).normalized()) }
    }

    fun flipTransform(horizontal: Boolean) {
        mutate { s -> s.copy(transform = s.transform.appendedFlip(s.docW, s.docH, horizontal)) }
    }

    fun setFreeRotation(degrees: Float) {
        mutate { s -> s.copy(transform = s.transform.withFreeRotation(degrees)) }
    }

    /** (تصحيح F19-استعادة) التدوير الحر أثناء سحب المنزلق: بلا تسجيل تاريخ لكل
     *  إطار — يسجّل beginSliderCommit مرة واحدة وتُغلق endSliderCommit.
     *  بدونه يغرق سحب واحد تاريخ التراجع ويمسح الإدخالات القديمة. */
    fun setFreeRotationLive(degrees: Float) {
        mutate(record = false) { s -> s.copy(transform = s.transform.withFreeRotation(degrees)) }
    }

    fun applyCrop(rect: FloatRect?) {
        mutate { s -> s.copy(transform = s.transform.withCrop(rect)) }
    }

    fun resetCrop() {
        mutate { s -> s.copy(transform = s.transform.withCrop(null)) }
    }

    fun resetTransform() {
        mutate { s -> s.copy(transform = ImageTransform()) }
    }

    // ------------------------------------------------------------------
    // تحكم القماش (EditorCanvasController)

    override fun onCanvasTap(docX: Float, docY: Float) {
        val s = _state.value
        val hit = layerAt(docX, docY, s)
        if (hit != s.selectedLayerId) {
            _state.value = _state.value.copy(selectedLayerId = hit)
        }
    }

    override fun onLayerMove(deltaDocX: Float, deltaDocY: Float) {
        if (_state.value.selectedLayerId == null) return
        mutate(record = false) { s ->
            s.copy(layers = s.layers.map { it.movedBy(deltaDocX, deltaDocY) })
        }
    }

    override fun onLayerGeo(centerX: Float, centerY: Float, scale: Float, rotation: Float) {
        // المقابض تمرّر عوامل مطلقة من بداية الإيماءة فتُطبّق على نسخة البداية
        // المحفوظة في beginGesture — لا تركيب متكرر يراكم التحجيم.
        val base = gestureLayer ?: _state.value.selected ?: return
        val safeScale = scale.coerceAtLeast(0.05f)
        mutate(record = false) { s ->
            val moved = when (base) {
                is EditLayer.Text -> base.copy(
                    centerX = centerX, centerY = centerY, rotation = rotation,
                    fontSizePx = (base.fontSizePx * safeScale).coerceAtLeast(8f)
                )
                is EditLayer.Image -> base.copy(
                    centerX = centerX, centerY = centerY, rotation = rotation,
                    docWidth = (base.docWidth * safeScale).coerceAtLeast(16f)
                )
                is EditLayer.Shape -> {
                    val cx = (base.x1 + base.x2) / 2f
                    val cy = (base.y1 + base.y2) / 2f
                    base.copy(
                        centerX = centerX, centerY = centerY, rotation = rotation,
                        x1 = centerX + (base.x1 - cx) * safeScale,
                        y1 = centerY + (base.y1 - cy) * safeScale,
                        x2 = centerX + (base.x2 - cx) * safeScale,
                        y2 = centerY + (base.y2 - cy) * safeScale
                    )
                }
                is EditLayer.Stroke -> base
            }
            s.copy(layers = s.layers.map { if (it.id == base.id) moved else it })
        }
    }

    override fun onEraseStrokeAt(docX: Float, docY: Float, start: Boolean) {
        if (start) eraseRecorded = false
        val s = _state.value
        val hit = layerAt(docX, docY, s) ?: return
        val layer = s.layers.firstOrNull { it.id == hit } as? EditLayer.Stroke ?: return
        if (!eraseRecorded) { history.record(snapshotOf(s)); eraseRecorded = true }
        mutate(record = false) { st ->
            st.copy(layers = st.layers.filterNot { it.id == layer.id })
        }
    }

    @Volatile private var liveStroke: EditLayer.Stroke? = null
    @Volatile private var liveShape: EditLayer.Shape? = null

    override fun onStrokePoint(docX: Float, docY: Float, start: Boolean) {
        val s = _state.value
        if (start) {
            val w = s.drawWidthScreenPx / s.previewScale
            liveStroke = EditLayer.Stroke(
                id = -1, centerX = 0f, centerY = 0f,
                points = listOf(Pair(docX, docY)),
                color = s.drawColor, widthPx = w
            )
        } else {
            liveStroke = liveStroke?.let { it.copy(points = it.points + Pair(docX, docY)) }
        }
        requestRender()
    }

    override fun onStrokeFinish() {
        val stroke = liveStroke
        liveStroke = null
        if (stroke != null && stroke.points.isNotEmpty()) {
            val cx = stroke.points.sumOf { it.first.toDouble() } / stroke.points.size
            val cy = stroke.points.sumOf { it.second.toDouble() } / stroke.points.size
            mutate { s ->
                s.copy(layers = s.layers + stroke.copy(id = idGen.getAndIncrement(), centerX = cx.toFloat(), centerY = cy.toFloat()))
            }
        } else requestRender()
    }

    override fun onShapeDrag(docX: Float, docY: Float, start: Boolean) {
        val s = _state.value
        if (start) {
            liveShape = EditLayer.Shape(
                id = -1, centerX = 0f, centerY = 0f,
                kind = s.shapeKind,
                x1 = docX, y1 = docY, x2 = docX, y2 = docY,
                strokeColor = s.shapeStrokeColor,
                fillColor = s.shapeFillColor,
                strokeWidthPx = s.shapeWidthScreenPx / s.previewScale,
                bubbleText = if (s.shapeKind == ShapeKind.BUBBLE) "" else null
            )
        } else {
            val ls = liveShape
            if (ls != null) {
                var x2 = docX
                var y2 = docY
                if (ls.kind == ShapeKind.SQUARE || ls.kind == ShapeKind.CIRCLE) {
                    // مربع/دائرة: ضلعان متساويان (باتجاه السحب)
                    val dx = docX - ls.x1
                    val dy = docY - ls.y1
                    val side = kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    x2 = ls.x1 + if (dx < 0f) -side else side
                    y2 = ls.y1 + if (dy < 0f) -side else side
                }
                liveShape = ls.copy(x2 = x2, y2 = y2)
            }
        }
        requestRender()
    }

    override fun onShapeDragFinish() {
        val shape = liveShape
        liveShape = null
        val tooSmall = shape != null &&
            kotlin.math.hypot((shape.x2 - shape.x1).toDouble(), (shape.y2 - shape.y1).toDouble()) < 8f
        if (shape != null && !tooSmall) {
            val cx = (shape.x1 + shape.x2) / 2f
            val cy = (shape.y1 + shape.y2) / 2f
            mutate { s ->
                s.copy(
                    layers = s.layers + shape.copy(id = idGen.getAndIncrement(), centerX = cx, centerY = cy),
                    selectedLayerId = null
                )
            }
        } else requestRender()
    }

    override fun cancelLive() = clearLive()

    private fun clearLive() {
        liveStroke = null
        liveShape = null
        requestRender()
    }

    // ------------------------------------------------------------------
    // الاستخدام الداخلي

    private fun layerAt(docX: Float, docY: Float, s: EditorUiState): Long? {
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

    private fun EditLayer.movedBy(dx: Float, dy: Float): EditLayer = when (this) {
        is EditLayer.Text -> if (id == _state.value.selectedLayerId) copy(centerX = centerX + dx, centerY = centerY + dy) else this
        is EditLayer.Image -> if (id == _state.value.selectedLayerId) copy(centerX = centerX + dx, centerY = centerY + dy) else this
        is EditLayer.Stroke -> if (id == _state.value.selectedLayerId)
            copy(centerX = centerX + dx, centerY = centerY + dy, points = points.map { Pair(it.first + dx, it.second + dy) }) else this
        is EditLayer.Shape -> if (id == _state.value.selectedLayerId)
            copy(centerX = centerX + dx, centerY = centerY + dy, x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy) else this
    }

    // ------------------------------------------------------------------
    // الحفظ

    fun saveCopy(baseName: String, format: ExportFormat, quality: Int) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch(AppDispatchers.io) {
            val s = _state.value
            val result = ImageExporter.saveAsCopy(getApplication(), imagePath, s.transform, s.layers, s.cutOps, baseName, format, quality)
            _state.value = _state.value.copy(saving = false)
            if (result.ok) _events.tryEmit(EditorEvent.Saved(result.path ?: ""))
            else _events.tryEmit(EditorEvent.Failed(R.string.ie_save_failed))
        }
    }

    fun replaceOriginal(quality: Int) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch(AppDispatchers.io) {
            val s = _state.value
            val result = ImageExporter.replaceOriginal(getApplication(), imagePath, s.transform, s.layers, s.cutOps, quality)
            _state.value = _state.value.copy(saving = false)
            if (result.ok) _events.tryEmit(EditorEvent.Saved(result.path ?: ""))
            else _events.tryEmit(EditorEvent.Failed(R.string.ie_replace_failed))
        }
    }
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

    /** حدود حرة للقص (start = بداية رسم جديد). */
    fun onLassoPoint(docX: Float, docY: Float, start: Boolean)

    /** نهاية رسم الحدود: تُثبَّت عملية القص. */
    fun onLassoFinish()

    /** لمسة العصا السحرية (قص الخلفية). */
    fun onCutTap(docX: Float, docY: Float)

    /** إلغاء أي عنصر حي قيد الإنشاء (تبديل أداة مثلاً). */
    fun cancelLive()
}
