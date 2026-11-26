package org.openfilemanager.app.imageedit.model

import android.graphics.Bitmap

/**
 * نماذج المحرّر. المبدأ المعماري: كل طبقة عنصر مستقل قابل للتعديل حتى التصدير،
 * وإحداثيات كل شيء بمساحة بكسل الصورة الأصلية المعترضة (doc space) — لا إحداثيات
 * شاشة — لذا تبقى العناصر صحيحة عند تغيّر مقاس المعاينة أو دقة التصدير.
 */

/** محرف قابل للتعديل (jpg, png, webp, bmp, heic, heif). */
val EDITABLE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

fun isEditableImage(path: String): Boolean =
    EDITABLE_IMAGE_EXTENSIONS.contains(path.substringAfterLast('.', "").lowercase())

/** ألوان الطبقات كـ ARGB طويل — لا اعتماد على Compose في النماذج. */
typealias Argb = Long

enum class EditorTool { NONE, ADJUST, CROP, CUT, ROTATE, TEXT, DRAW, SHAPES, IMAGE }

/** نمط ضربة الفرشاة: قلم حاد، فلوماستر شفاف عريض، أو خط نيون بتوهّج. */
enum class BrushStyle { PEN, MARKER, NEON }

enum class TextAlignment { START, CENTER, END }

/**
 * الخطوط: كل عنصر يحمل اسم عائلة الخط في نظام أندرويد ومجموعة العرض.
 * أندرويد لا يوفر خطوطاً عربية كثيرة، لذا الخطوط العربية هنا هي خطوط النظام
 * (نسخ/سانس بأوزان مختلفة) — ولإضافة خط مضمّن ضع ملف ttf في res/font وأضف عنصراً هنا.
 */
enum class EditorFontGroup { ARABIC, ENGLISH }

enum class EditorFontFamily(
    val systemName: String,
    val group: EditorFontGroup,
    /** خط مضمّن في assets/fonts: يعمل على كل الأجهزة بغض النظر عن خطوط النظام. */
    val asset: String? = null,
    val assetBold: String? = null
) {
    // عام (يُستخدم للقيم القديمة المحفوظة)
    DEFAULT("sans-serif", EditorFontGroup.ENGLISH),
    SANS("sans-serif", EditorFontGroup.ENGLISH),
    SERIF("serif", EditorFontGroup.ENGLISH),
    MONOSPACE("monospace", EditorFontGroup.ENGLISH),
    CURSIVE("cursive", EditorFontGroup.ENGLISH),

    // عربي — خطوط مضمّنة في التطبيق (assets/fonts)
    AR_AMIRI("serif", EditorFontGroup.ARABIC, "Amiri-Regular.ttf", "Amiri-Bold.ttf"),
    AR_SCHEHERAZADE("serif", EditorFontGroup.ARABIC, "ScheherazadeNew-Regular.ttf", "ScheherazadeNew-Bold.ttf"),
    AR_TAJAWAL("sans-serif", EditorFontGroup.ARABIC, "Tajawal-Regular.ttf", "Tajawal-Bold.ttf"),
    AR_ALMARAI("sans-serif", EditorFontGroup.ARABIC, "Almarai-Regular.ttf", "Almarai-Bold.ttf"),
    AR_LATEEF("serif", EditorFontGroup.ARABIC, "Lateef-Regular.ttf", null),

    // عربي — خطوط النظام
    AR_NASKH("serif", EditorFontGroup.ARABIC),
    AR_SANS("sans-serif", EditorFontGroup.ARABIC),
    AR_MEDIUM("sans-serif-medium", EditorFontGroup.ARABIC),
    AR_LIGHT("sans-serif-light", EditorFontGroup.ARABIC),
    AR_CONDENSED("sans-serif-condensed", EditorFontGroup.ARABIC),
    AR_BLACK("sans-serif-black", EditorFontGroup.ARABIC),

    // إنجليزي
    EN_THIN("sans-serif-thin", EditorFontGroup.ENGLISH),
    EN_LIGHT("sans-serif-light", EditorFontGroup.ENGLISH),
    EN_MEDIUM("sans-serif-medium", EditorFontGroup.ENGLISH),
    EN_BLACK("sans-serif-black", EditorFontGroup.ENGLISH),
    EN_CONDENSED("sans-serif-condensed", EditorFontGroup.ENGLISH),
    EN_CASUAL("casual", EditorFontGroup.ENGLISH),
    EN_SMALLCAPS("sans-serif-smallcaps", EditorFontGroup.ENGLISH),
    EN_SERIF_MONO("serif-monospace", EditorFontGroup.ENGLISH);

    companion object {
        /** القوائم التي تُعرض في اللوحة (بلا القيم العامة القديمة). */
        val ARABIC_LIST = listOf(AR_AMIRI, AR_SCHEHERAZADE, AR_TAJAWAL, AR_ALMARAI, AR_LATEEF, AR_NASKH, AR_SANS, AR_MEDIUM, AR_LIGHT, AR_CONDENSED, AR_BLACK)
        val ENGLISH_LIST = listOf(
            SANS, SERIF, MONOSPACE, CURSIVE, EN_THIN, EN_LIGHT, EN_MEDIUM, EN_BLACK,
            EN_CONDENSED, EN_CASUAL, EN_SMALLCAPS, EN_SERIF_MONO
        )
    }
}

/** أنواع الأشكال المدعومة (المحرّك نفسه يرسمها بلا مكتبات خارجية). */
enum class ShapeKind {
    LINE, ARROW, DOUBLE_ARROW, BLOCK_ARROW,
    RECT, SQUARE, ROUNDED_RECT, CIRCLE, ELLIPSE,
    TRIANGLE, RIGHT_TRIANGLE, DIAMOND, PENTAGON, HEXAGON,
    STAR, HEART, PLUS, CHEVRON, BUBBLE;

    /** خط أو سهم (يُعرَّف بطرفين لا بصندوق). */
    val isLinear: Boolean get() = this == LINE || this == ARROW || this == DOUBLE_ARROW
}

/**
 * الطبقة الأساسية لكل عنصر يُضاف فوق الصورة. كل عنصر يبقى قابلاً للتحريك
 * والتحجيم والتدوير والحذف وإعادة الترتيب حتى لحظة التصدير.
 */
sealed interface EditLayer {
    val id: Long
    /** مركز الطبقة بإحداثيات الصورة الأصلية. */
    val centerX: Float
    val centerY: Float
    /** تدوير الطبقة نفسها بالدرجات (باتجاه الساعة). */
    val rotation: Float
    /** الشفافية 0..1. */
    val opacity: Float
    val visible: Boolean

    /** طبقة نص: تُخزَّن نصاً قابلاً للتعديل ولا تُرَسترَف إلا عند العرض/التصدير. */
    data class Text(
        override val id: Long,
        override val centerX: Float,
        override val centerY: Float,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
        override val visible: Boolean = true,
        val text: String,
        val fontSizePx: Float,
        val color: Argb = 0xFFFFFFFF,
        val bgColor: Argb? = null,
        val alignment: TextAlignment = TextAlignment.CENTER,
        val fontFamily: EditorFontFamily = EditorFontFamily.DEFAULT,
        val bold: Boolean = false,
        val shadow: Boolean = false,
        val outline: Boolean = false,
        val outlineWidthPx: Float = 0f,
        // خيارات تنسيق إضافية
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        /** مضاعف تباعد الأسطر. */
        val lineSpacing: Float = 1f,
        /** تباعد الحروف (em). */
        val letterSpacing: Float = 0f,
        /** أقصى عرض لصندوق النص كنسبة من عرض الصورة (0.2..1). */
        val boxWidthFrac: Float = 0.9f,
        /** لون الحدّ (null = تلقائي عكس لون النص). */
        val outlineColor: Argb? = null,
        val shadowColor: Argb = 0xB3000000
    ) : EditLayer

    /** طبقة صورة مضافة فوق الأصل (ستيكر/تراكب). */
    data class Image(
        override val id: Long,
        override val centerX: Float,
        override val centerY: Float,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
        override val visible: Boolean = true,
        /** عرض العرض بإحداثيات المستند. */
        val docWidth: Float,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        val bitmap: Bitmap
    ) : EditLayer

    /** ضربة فرشاة واحدة — كل ضربة طبقة مستقلة فتُحذف ضربة واحدة بسهولة. */
    data class Stroke(
        override val id: Long,
        override val centerX: Float,
        override val centerY: Float,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
        override val visible: Boolean = true,
        val points: List<Pair<Float, Float>>,
        val color: Argb,
        val widthPx: Float,
        val style: BrushStyle = BrushStyle.PEN
    ) : EditLayer

    /** شكل هندسي أو سهم أو بالون كلام. */
    data class Shape(
        override val id: Long,
        override val centerX: Float,
        override val centerY: Float,
        override val rotation: Float = 0f,
        override val opacity: Float = 1f,
        override val visible: Boolean = true,
        val kind: ShapeKind,
        /** الصندوق المحدد (أو طرفا الخط/السهم) بإحداثيات المستند. */
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val strokeColor: Argb,
        val fillColor: Argb? = null,
        val strokeWidthPx: Float,
        /** نص داخل بالون الكلام (فقط لنوع BUBBLE). */
        val bubbleText: String? = null,
        val textColor: Argb = 0xFF000000
    ) : EditLayer
}

/** صيغ التصدير. */
enum class ExportFormat(val extension: String, val compressFormat: Bitmap.CompressFormat) {
    JPEG("jpg", Bitmap.CompressFormat.JPEG),
    PNG("png", Bitmap.CompressFormat.PNG),
    WEBP("webp", Bitmap.CompressFormat.WEBP_LOSSY)
}

/**
 * أي الطبقات تتفاعل مع اللمس في كل أداة. كانت كل الطبقات تلتقط اللمس
 * في كل الأدوات: صورة مضافة تغطي اللوحة كانت تبتلع سحب رسم الأشكال والنص، ويبقى
 * إطارها ظاهراً فوق الأداة الأخرى. الآن لكل أداة نوع طبقتها فقط.
 */
fun EditLayer.isInteractiveWith(tool: EditorTool): Boolean = when (tool) {
    EditorTool.TEXT -> this is EditLayer.Text
    EditorTool.IMAGE -> this is EditLayer.Image
    EditorTool.SHAPES -> this is EditLayer.Shape
    EditorTool.NONE -> this !is EditLayer.Stroke
    EditorTool.DRAW, EditorTool.CROP, EditorTool.ROTATE, EditorTool.CUT, EditorTool.ADJUST -> false
}

/** أوضاع أداة القصّ: القصّ الحر، مسح منطقة، قصّ الخلفية باللمس، الممحاة، الاستعادة، والموزاييك. */
enum class CutMode {
    /** رسم حدود حرة (أو شكل): يُبقى ما بداخلها ويصير الخارج شفافاً. */
    KEEP,
    /** رسم حدود (أو شكل): يُمسح ما بداخلها. */
    ERASE,
    /** لمسة: تمسح المنطقة المتصلة المشابهة اللون (قص الخلفية). */
    WAND,
    /** فرشاة تمسح بكسلات الصورة الأساس مباشرة. */
    ERASER,
    /** فرشاة تعيد بكسلات من الصورة الأصلية فوق مناطق قُصَّت أو مُسحت. */
    RESTORE,
    /** فرشاة تخفي التفاصيل ببلورات كبيرة (للخصوصية: وجوه، لوحات...). */
    MOSAIC
}

/** نطاق التنعيم/الحساسية المشترك: قيمة مئوية 0..100. */
const val CUT_TOLERANCE_DEFAULT = 30
const val CUT_EDGE_DEFAULT = 35

/** عملية قص على الصورة الأساس (بإحداثيات المستند). */
sealed interface CutOp {
    /** تنعيم الحافة 0..100 (0 = حادة). */
    val feather: Int

    data class Keep(val points: List<Pair<Float, Float>>, override val feather: Int = 0) : CutOp
    data class Erase(val points: List<Pair<Float, Float>>, override val feather: Int = 0) : CutOp

    data class Wand(
        val x: Float,
        val y: Float,
        val tolerance: Int,
        /** إحكام الحواف 0..100: يمنع النزيف عبر الحواف الناعمة. */
        val edgeStop: Int = CUT_EDGE_DEFAULT,
        override val feather: Int = 0
    ) : CutOp

    /** إزالة الخلفية تلقائياً: يُقدَّر لون الإطار وتُمسح كل ما يتصل به منه. */
    data class AutoBg(
        val tolerance: Int,
        val edgeStop: Int = CUT_EDGE_DEFAULT,
        override val feather: Int = 0
    ) : CutOp

    /** فرشاة مسح بكسلات: خط مستدير الأطراف؛ [hardness] 0..100 (100 = حافة حادة). */
    data class EraseStroke(
        val points: List<Pair<Float, Float>>,
        val widthDoc: Float,
        val hardness: Int = 100
    ) : CutOp {
        override val feather: Int get() = 0
    }

    /** فرشاة استعادة: تعيد بكسلات الصورة الأصلية داخل مسارها. */
    data class Restore(
        val points: List<Pair<Float, Float>>,
        val widthDoc: Float,
        val hardness: Int = 100
    ) : CutOp {
        override val feather: Int get() = 0
    }

    /** فرشاة موزاييك: تُرسم نسخة مبكسلة من الأصل داخل مسارها. */
    data class Pixelate(
        val points: List<Pair<Float, Float>>,
        val widthDoc: Float,
        /** حجم البلوكة كنسبة من عرض الصورة. */
        val cellFrac: Float = 0.03f
    ) : CutOp {
        override val feather: Int get() = 0
    }
}

/** هل العملية تُضبط بحساسية قصّ الخلفية (عصا/تلقائي)؟ */
val CutOp.acceptsTolerance: Boolean
    get() = this is CutOp.Wand || this is CutOp.AutoBg

/** هل لعملية حدودية (يدوية) تنعيم حافة؟ */
val CutOp.acceptsFeather: Boolean
    get() = this is CutOp.Keep || this is CutOp.Erase || this is CutOp.Wand || this is CutOp.AutoBg

/** يُعيد العملية نفسها بقيمة تنعيم جديدة. */
fun CutOp.withFeather(value: Int): CutOp {
    val v = value.coerceIn(0, 100)
    return when (this) {
        is CutOp.Keep -> copy(feather = v)
        is CutOp.Erase -> copy(feather = v)
        is CutOp.Wand -> copy(feather = v)
        is CutOp.AutoBg -> copy(feather = v)
        else -> this
    }
}

/** يُعيد عملية العصا/التلقائي بقيم حساسية وإحكام حواف جديدة. */
fun CutOp.withTolerance(tolerance: Int, edgeStop: Int): CutOp = when (this) {
    is CutOp.Wand -> copy(tolerance = tolerance.coerceIn(0, 100), edgeStop = edgeStop.coerceIn(0, 100))
    is CutOp.AutoBg -> copy(tolerance = tolerance.coerceIn(0, 100), edgeStop = edgeStop.coerceIn(0, 100))
    else -> this
}
