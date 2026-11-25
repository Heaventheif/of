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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.engine.CutEngine
import org.openfilemanager.app.imageedit.engine.ImageDecoder
import org.openfilemanager.app.imageedit.engine.ImageExporter
import org.openfilemanager.app.imageedit.engine.ImageRenderer
import org.openfilemanager.app.imageedit.model.BrushStyle
import org.openfilemanager.app.imageedit.model.CUT_EDGE_DEFAULT
import org.openfilemanager.app.imageedit.model.CUT_TOLERANCE_DEFAULT
import org.openfilemanager.app.imageedit.model.CutMode
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.core.imageedit.CutShape
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.model.EditorTool
import org.openfilemanager.app.imageedit.model.ExportFormat
import org.openfilemanager.app.imageedit.model.ShapeKind
import org.openfilemanager.app.imageedit.model.TextAlignment
import org.openfilemanager.app.imageedit.model.acceptsFeather
import org.openfilemanager.app.imageedit.model.acceptsTolerance
import org.openfilemanager.app.imageedit.model.isInteractiveWith
import org.openfilemanager.app.imageedit.model.withFeather
import org.openfilemanager.app.imageedit.model.withTolerance
import org.openfilemanager.core.imageedit.Adjust
import org.openfilemanager.core.imageedit.CutAlgorithms
import org.openfilemanager.core.imageedit.CutShapes
import org.openfilemanager.core.imageedit.EditHistory
import org.openfilemanager.core.imageedit.FilterPreset
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

    private data class Snapshot(
        val transform: ImageTransform,
        val adjust: Adjust,
        val layers: List<EditLayer>,
        val cutOps: List<CutOp> = emptyList()
    )

    private val _state = MutableStateFlow(EditorUiState(name = imagePath.substringAfterLast('/')))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _liveCut = MutableStateFlow<LiveCut?>(null)
    /** الحدّ الحر قيد الرسم على القماش (يقرأه القماش لرسم التراكب). */
    val liveCut: StateFlow<LiveCut?> = _liveCut.asStateFlow()

    private val _showOriginal = MutableStateFlow(false)
    /** «قبل»: اضغط مع الاستمرار على زر المقارنة لعرض الأصل بلا أي تعديل. */
    val showOriginal: StateFlow<Boolean> = _showOriginal.asStateFlow()

    private val _thumbBase = MutableStateFlow<Bitmap?>(null)
    /** نسخة مصغّرة من الأصل لمعاينة الفلاتر في لوحة التعديل. */
    val thumbBase: StateFlow<Bitmap?> = _thumbBase.asStateFlow()

    private var basePreview: Bitmap? = null
    private var originalPreview: Bitmap? = null
    private var exifOrientation = 1
    private val history = EditHistory<Snapshot>()
    private var initialSnap: Snapshot = Snapshot(ImageTransform(), Adjust(), emptyList())
    private var renderJob: Job? = null
    /** (تصحيح) إشارة إعادة رسم مدموجة: طلبات كثيرة متلاحقة = رسم واحد بأحدث حالة. */
    private val renderSignal = Channel<Unit>(Channel.CONFLATED)
    private val idGen = AtomicLong(1)
    private var gestureRecording = false
    private var sliderRecording = false
    private var gestureLayer: EditLayer? = null
    private var eraseRecorded = false
    private var lastTextRecordAt = 0L
    /** نقاط فرش القصّ الجارية (قائمة غير قابلة للتعديل تُستبدل بكل نقطة). */
    private var brushPoints: List<Pair<Float, Float>>? = null

    init {
        viewModelScope.launch(AppDispatchers.io) { load() }
        startRenderLoop()
        viewModelScope.launch { _showOriginal.drop(1).collect { requestRender() } }
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
            _thumbBase.value = runCatching {
                val k = 72f / maxOf(decoded.width, decoded.height)
                Bitmap.createScaledBitmap(
                    decoded, maxOf(1, (decoded.width * k).toInt()), maxOf(1, (decoded.height * k).toInt()), true
                )
            }.getOrNull()
            val scale = decoded.width.toFloat() / bounds.width
            initialSnap = Snapshot(ImageTransform(), Adjust(), emptyList())
            _state.value = _state.value.copy(
                loading = false,
                name = path.fileName?.toString() ?: imagePath.substringAfterLast('/'),
                docW = bounds.width.toFloat(),
                docH = bounds.height.toFloat(),
                previewScale = scale,
                transform = ImageTransform(),
                adjust = Adjust(),
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
                if (_showOriginal.value) {
                    // «قبل»: الأصل بلا قصّ ولا فلاتر ولا طبقات (يُرسم مرة واحدة ويُخزَّن)
                    val orig = originalPreview ?: runCatching {
                        ImageRenderer.render(base, ImageTransform(), emptyList(), s.previewScale, opaqueBackground = false)
                    }.getOrNull()?.also { originalPreview = it }
                    if (orig != null) _preview.value = orig
                    continue
                }
                // أثناء أداة القصّ تُعرض اللوحة الكاملة قبل القصّ لتظهر المنطقة في سياقها
                val t = if (s.tool == EditorTool.CROP) s.transform.withCrop(null) else s.transform
                val extras = buildList {
                    liveStroke?.let { add(it) }
                    liveShape?.let { add(it) }
                }
                // (تصحيح M4) فشل الرسم لا يُهمل صامتاً: محاولة إضافية ثم إبقاء آخر معاينة ناجحة
                val renderBase = runCatching { cutBaseFor(base, s) }.getOrDefault(base)
                var rendered = runCatching {
                    ImageRenderer.render(renderBase, t, s.layers + extras, s.previewScale, opaqueBackground = false, adjust = s.adjust)
                }.getOrNull()
                if (rendered == null) {
                    rendered = runCatching {
                        ImageRenderer.render(renderBase, t, s.layers + extras, s.previewScale, opaqueBackground = false, adjust = s.adjust)
                    }.getOrNull()
                }
                if (rendered != null) _preview.value = rendered
            }
        }
    }

    /** آخر نتيجة للعمليات المُثبَّتة (تُستعمل فقط من خيط الرسم المتسلسل). */
    private var cutCache: Pair<List<CutOp>, Bitmap>? = null

    /** عنصر فرشاة قصّ حي (ممحاة/استعادة/موزاييك) يُطبَّق فوق العمليات المُثبَّتة. */
    @Volatile private var liveCutOp: CutOp? = null

    /**
     * الصورة الأساس بعد عمليات القصّ المُثبَّتة وعنصر الفرشاة الحي إن وُجد.
     * المُثبَّتة تُحسب تزايدياً وتُخزَّن مؤقتاً؛ الحي يُطبَّق على نسخة من نتيجتها
     * كل إطار، فلا يعاد بناء كل العمليات أثناء سحب الفرشاة.
     */
    private fun cutBaseFor(base: Bitmap, s: EditorUiState): Bitmap {
        val live = liveCutOp
        val ops = s.cutOps
        if (ops.isEmpty() && live == null) return base
        val committed = if (ops.isEmpty()) base else committedBase(base, ops, s.docW)
        if (live == null) return committed
        return runCatching { CutEngine.apply(committed, listOf(live), s.docW, original = base) }.getOrDefault(committed)
    }

    private fun committedBase(base: Bitmap, ops: List<CutOp>, docW: Float): Bitmap {
        val c = cutCache
        if (c != null && c.first == ops) return c.second
        val result = if (c != null && ops.size > c.first.size && ops.subList(0, c.first.size) == c.first)
            CutEngine.apply(c.second, ops.drop(c.first.size), docW, original = base)
        else CutEngine.apply(base, ops, docW)
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
        CutEngine.releaseCaches()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // بنية التعديل والتاريخ

    private fun snapshotOf(s: EditorUiState) = Snapshot(s.transform, s.adjust, s.layers, s.cutOps)

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
            s.copy(
                transform = snap.transform, adjust = snap.adjust, layers = snap.layers,
                cutOps = snap.cutOps, selectedLayerId = selection
            )
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

    fun setDrawSettings(
        color: Long? = null, widthScreenPx: Float? = null, eraser: Boolean? = null, style: BrushStyle? = null
    ) {
        _state.value = _state.value.copy(
            drawColor = color ?: _state.value.drawColor,
            drawWidthScreenPx = widthScreenPx ?: _state.value.drawWidthScreenPx,
            drawEraser = eraser ?: _state.value.drawEraser,
            drawStyle = style ?: _state.value.drawStyle
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

    // ---- التعديلات (الإضاءة/الألوان/الفلاتر)

    /** يطبّق فلتراً جاهزاً: قيمه تصير قيم الشرائح فتبقى قابلة للضبط الدقيق. */
    fun applyFilter(filter: FilterPreset) {
        mutate { it.copy(adjust = filter.adjust) }
    }

    /** تعديل أحد مفردات الضبط بلا تسجيل لكل إطار؛ يسجّله التزام المنزلق مرة. */
    fun updateAdjust(record: Boolean = false, block: (Adjust) -> Adjust) {
        mutate(record) { it.copy(adjust = block(it.adjust)) }
    }

    fun resetAdjust() {
        if (_state.value.adjust.isIdentity) return
        mutate { it.copy(adjust = Adjust()) }
    }

    /** «قبل/بعد»: عرض الأصل طوال الضغط على زر المقارنة. */
    fun setShowOriginal(show: Boolean) {
        _showOriginal.value = show
    }

    // ---- القصّ الحر وقصّ الخلفية

    fun setCutSettings(mode: CutMode? = null, shape: CutShape? = null) {
        val cur = _state.value
        _state.value = cur.copy(cutMode = mode ?: cur.cutMode, cutShape = shape ?: cur.cutShape)
        if (mode != null || shape != null) clearLive()
    }

    /** سماكة وصلابة فرش الممحاة/الاستعادة/الموزاييك. */
    fun setCutBrush(sizeScreenPx: Float? = null, hardness: Int? = null) {
        val cur = _state.value
        _state.value = cur.copy(
            cutBrushScreenPx = (sizeScreenPx ?: cur.cutBrushScreenPx).coerceIn(4f, 300f),
            cutBrushHardness = (hardness ?: cur.cutBrushHardness).coerceIn(0, 100)
        )
    }

    /** استعادة الصورة الأصلية بإلغاء كل عمليات القص (قابلة للتراجع). */
    fun resetCut() {
        if (_state.value.cutOps.isEmpty()) return
        mutate { it.copy(cutOps = emptyList()) }
    }

    /** حذف آخر عملية قصّ فقط (لمسة عصا زائدة مثلاً) — قابلة للتراجع. */
    fun removeLastCut() {
        if (_state.value.cutOps.isEmpty()) return
        mutate { it.copy(cutOps = it.cutOps.dropLast(1)) }
    }

    /** إزالة الخلفية تلقائياً: يُقدَّر لون الإطار وتُمسح كل ما يتصل به منه. */
    fun autoRemoveBackground() {
        mutate { it.copy(cutOps = it.cutOps + CutOp.AutoBg(it.cutTolerance, it.cutEdge, it.cutFeather)) }
    }

    /**
     * ضبط الحساسية/إحكام الحواف/التنعيم. يُثبَّت على آخر عملية قصّ إن كانت تقبلها
     * (عصا/تلقائي: الثلاثة؛ حدود يدوية: التنعيم فقط)، فيعدّل المستخدم لمسته
     * الأخيرة بدل تكرارها — ويبقى قابلاً للتراجع عبر التزام المنزلق.
     */
    fun setCutTuning(tolerance: Int? = null, edgeStop: Int? = null, feather: Int? = null) {
        val cur = _state.value
        val newTol = (tolerance ?: cur.cutTolerance).coerceIn(0, 100)
        val newEdge = (edgeStop ?: cur.cutEdge).coerceIn(0, 100)
        val newFeather = (feather ?: cur.cutFeather).coerceIn(0, 100)
        mutate(record = false) { st ->
            val last = st.cutOps.lastOrNull()
            val retuned = last?.let { op ->
                when {
                    op.acceptsTolerance -> op.withTolerance(newTol, newEdge).withFeather(newFeather)
                    op.acceptsFeather -> op.withFeather(newFeather)
                    else -> op
                }
            }
            st.copy(
                cutTolerance = newTol, cutEdge = newEdge, cutFeather = newFeather,
                cutOps = if (retuned != null && retuned != last) st.cutOps.dropLast(1) + retuned else st.cutOps
            )
        }
    }

    /** لمسة العصا: تمسح المنطقة المتصلة المشابهة للون تحت الإصبع. */
    override fun onCutTap(docX: Float, docY: Float) {
        val s = _state.value
        if (docX < 0f || docY < 0f || docX > s.docW || docY > s.docH) return
        mutate { it.copy(cutOps = it.cutOps + CutOp.Wand(docX, docY, it.cutTolerance, it.cutEdge, it.cutFeather)) }
    }

    /** نقطة حدّ حر (start = بداية رسم جديد). */
    override fun onCutPathPoint(docX: Float, docY: Float, start: Boolean) {
        val s = _state.value
        val cur = _liveCut.value
        _liveCut.value = if (start || cur == null) {
            LiveCut(s.cutMode, listOf(Pair(docX, docY)))
        } else {
            cur.copy(points = cur.points + Pair(docX, docY))
        }
        requestRender()
    }

    /** حدود شكل قصّ (مربع/دائرة...) أثناء السحب. */
    override fun onCutShapePoints(points: List<Pair<Float, Float>>) {
        _liveCut.value = LiveCut(_state.value.cutMode, points)
        requestRender()
    }

    /** نهاية رسم الحدّ: تخفيف وتنعيم النقاط ثم تثبيت عملية الإبقاء/المسح. */
    override fun onCutPathFinish() {
        val live = _liveCut.value
        _liveCut.value = null
        val s = _state.value
        if (live == null) { requestRender(); return }
        // تخفيف نقاط اللمس ثم قصّ الزوايا: حدّ مستقر وناعم لا خط مكسور بإصبع مرتجف
        val pts = CutAlgorithms.smoothClosed(
            CutAlgorithms.thinOut(live.points, 1.5f / s.previewScale, { it.first }, { it.second })
        )
        if (!CutShapes.isValid(pts, 2f)) { requestRender(); return }
        val op: CutOp = if (live.mode == CutMode.ERASE) CutOp.Erase(pts, s.cutFeather) else CutOp.Keep(pts, s.cutFeather)
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

    /** نقطة فرشاة قصّ (ممحاة/استعادة/موزاييك): start = بداية ضربة جديدة. */
    override fun onCutBrushPoint(docX: Float, docY: Float, start: Boolean) {
        val s = _state.value
        if (start || brushPoints == null) brushPoints = listOf(Pair(docX, docY))
        else brushPoints = brushPoints!! + Pair(docX, docY)
        publishLiveBrush()
        requestRender()
    }

    private fun publishLiveBrush() {
        val s = _state.value
        val pts = brushPoints ?: return
        val w = s.cutBrushScreenPx / s.previewScale
        liveCutOp = when (s.cutMode) {
            CutMode.ERASER -> CutOp.EraseStroke(pts, w, s.cutBrushHardness)
            CutMode.RESTORE -> CutOp.Restore(pts, w, s.cutBrushHardness)
            CutMode.MOSAIC -> CutOp.Pixelate(pts, w)
            else -> null
        }
        _liveCut.value = LiveCut(s.cutMode, pts, s.cutBrushScreenPx)
    }

    /** نهاية ضربة الفرشاة: تُثبَّت العملية التي كانت تُعرض حيّاً. */
    override fun onCutBrushFinish() {
        val op = liveCutOp
        liveCutOp = null
        brushPoints = null
        _liveCut.value = null
        if (op != null) mutate { it.copy(cutOps = it.cutOps + op) } else requestRender()
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
                color = s.drawColor, widthPx = w, style = s.drawStyle
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
        liveCutOp = null
        brushPoints = null
        _liveCut.value = null
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

    /** @param scale تغيير حجم المخرَج: 1 = القرار الأصلي (بعد القصّ)، وأقل للتصغير. */
    fun saveCopy(baseName: String, format: ExportFormat, quality: Int, scale: Float = 1f) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch(AppDispatchers.io) {
            val s = _state.value
            val result = ImageExporter.saveAsCopy(
                getApplication(), imagePath, s.transform, s.layers, s.cutOps,
                baseName, format, quality, adjust = s.adjust, scale = scale
            )
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
            val result = ImageExporter.replaceOriginal(
                getApplication(), imagePath, s.transform, s.layers, s.cutOps, quality, adjust = s.adjust
            )
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
