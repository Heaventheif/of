package org.openfilemanager.app.imageedit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import android.graphics.RectF
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.ImageEditorViewModel
import org.openfilemanager.app.imageedit.EditorUiState
import org.openfilemanager.app.imageedit.model.BrushStyle
import org.openfilemanager.app.imageedit.model.EditLayer
import org.openfilemanager.app.imageedit.engine.ImageRenderer
import org.openfilemanager.app.imageedit.model.EditorFontFamily
import org.openfilemanager.app.imageedit.model.ShapeKind
import org.openfilemanager.app.imageedit.model.TextAlignment
import org.openfilemanager.core.imageedit.CropMath
import org.openfilemanager.core.imageedit.FloatRect

/** أقصى ارتفاع للوحة الأداة: تضيّقه الشاشة عند ظهور لوحة المفاتيح كي تبقى الصورة ظاهرة. */
val LocalPanelMaxHeight = androidx.compose.runtime.compositionLocalOf { 300.dp }

@Composable
fun PanelColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // سقف لارتفاع اللوحة كي لا تبتلع القماش وتبقى الصورة ظاهرة للتعديل
            .heightIn(max = LocalPanelMaxHeight.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun PanelLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onCommit: (Float, Boolean) -> Unit
) {
    var latest by remember { mutableStateOf(value) }
    Column {
        PanelLabel(label)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { latest = it; onCommit(it, false) },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onCommit(latest, true) },
            // مقبض دائري صغير بدل الشريط العمودي
            thumb = {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        )
    }
}

/** لوحة الخيارات الخاصة بالأداة المحددة — تظهر فوق شريط الأدوات. */
@Composable
fun EditorToolPanel(
    vm: ImageEditorViewModel,
    state: EditorUiState,
    cropRect: FloatRect?,
    onCropRectChange: (FloatRect?) -> Unit
) {
    // AnimatedVisibility يرسم أبناءه فوق بعض كما في Box؛ لوحات تُخرج أكثر من عنصر
    // (مثل الأشكال: صف الأنواع ثم لوحة الألوان) كانت تتراكب. التغليف بعمود يرصّها رأسياً.
    Column(Modifier.fillMaxWidth()) {
    when (state.tool) {
        org.openfilemanager.app.imageedit.model.EditorTool.ADJUST -> AdjustToolPanel(vm, state)
        org.openfilemanager.app.imageedit.model.EditorTool.TEXT -> TextToolPanel(vm, state)
        org.openfilemanager.app.imageedit.model.EditorTool.CROP -> CropToolPanel(vm, state, cropRect, onCropRectChange)
        org.openfilemanager.app.imageedit.model.EditorTool.IMAGE -> ImageToolPanel(vm, state)
        org.openfilemanager.app.imageedit.model.EditorTool.DRAW -> DrawToolPanel(vm, state)
        org.openfilemanager.app.imageedit.model.EditorTool.SHAPES -> ShapesToolPanel(vm, state)
        org.openfilemanager.app.imageedit.model.EditorTool.ROTATE -> RotateToolPanel(vm, state)
        org.openfilemanager.app.imageedit.model.EditorTool.CUT -> CutToolPanel(vm, state)
        org.openfilemanager.app.imageedit.model.EditorTool.NONE -> {}
    }
    }
}

// ---------------------------------------------------------------------
// النص — لوحة بأقسام: النص | الخط | التنسيق | اللون | التأثيرات | الطبقة

private enum class TextTab(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: Int) {
    TEXT(Icons.Outlined.TextFields, R.string.ie_tab_text),
    FONT(Icons.Outlined.FontDownload, R.string.ie_tab_font),
    STYLE(Icons.Outlined.FormatSize, R.string.ie_tab_style),
    COLOR(Icons.Outlined.Palette, R.string.ie_tab_color),
    EFFECTS(Icons.Outlined.AutoAwesome, R.string.ie_tab_effects),
    LAYER(Icons.Outlined.Layers, R.string.ie_tab_layer)
}

@Composable
private fun TextToolPanel(vm: ImageEditorViewModel, state: EditorUiState) {
    val sel = state.selected as? EditLayer.Text
    if (sel == null) {
        PanelColumn {
            PanelLabel(stringResource(R.string.ie_text_intro))
            FilledTonalButton(onClick = { vm.addTextLayer() }) {
                Icon(Icons.Outlined.TextFields, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ie_add_text))
            }
        }
        return
    }
    var tab by remember { mutableStateOf(TextTab.TEXT) }
    Column(Modifier.fillMaxWidth()) {
        // شريط الأقسام
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(stringResource(t.label), maxLines = 1) },
                    leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }
                )
            }
        }
        // محتوى القسم
        PanelColumn {
            when (tab) {
                TextTab.TEXT -> TextContentSection(vm, sel)
                TextTab.FONT -> TextFontSection(vm, sel)
                TextTab.STYLE -> TextStyleSection(vm, sel, state)
                TextTab.COLOR -> {
                    PanelLabel(stringResource(R.string.ie_color))
                    EditorColorRow(sel.color) { c -> vm.updateSelectedText { it.copy(color = c) } }
                }
                TextTab.EFFECTS -> TextEffectsSection(vm, sel, state)
                TextTab.LAYER -> LayerCommonRow(vm, state)
            }
        }
    }
}

@Composable
private fun TextContentSection(vm: ImageEditorViewModel, sel: EditLayer.Text) {
    OutlinedTextField(
        value = sel.text,
        onValueChange = { v -> vm.updateSelectedText { it.copy(text = v) } },
        label = { Text(stringResource(R.string.ie_text_hint)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1, maxLines = 4
    )
}

/** اسم عرض الخط الإنجليزي (أسماء عائلات الخطوط لا تُترجم). */
private fun englishFontName(f: EditorFontFamily): String = when (f) {
    EditorFontFamily.SANS -> "Sans"
    EditorFontFamily.SERIF -> "Serif"
    EditorFontFamily.MONOSPACE -> "Mono"
    EditorFontFamily.CURSIVE -> "Cursive"
    EditorFontFamily.EN_THIN -> "Thin"
    EditorFontFamily.EN_LIGHT -> "Light"
    EditorFontFamily.EN_MEDIUM -> "Medium"
    EditorFontFamily.EN_BLACK -> "Black"
    EditorFontFamily.EN_CONDENSED -> "Condensed"
    EditorFontFamily.EN_CASUAL -> "Casual"
    EditorFontFamily.EN_SMALLCAPS -> "Small caps"
    EditorFontFamily.EN_SERIF_MONO -> "Serif mono"
    else -> "Default"
}

@Composable
private fun arabicFontName(f: EditorFontFamily): String = stringResource(
    when (f) {
        EditorFontFamily.AR_AMIRI -> R.string.ie_font_ar_amiri
        EditorFontFamily.AR_SCHEHERAZADE -> R.string.ie_font_ar_scheherazade
        EditorFontFamily.AR_TAJAWAL -> R.string.ie_font_ar_tajawal
        EditorFontFamily.AR_ALMARAI -> R.string.ie_font_ar_almarai
        EditorFontFamily.AR_LATEEF -> R.string.ie_font_ar_lateef
        EditorFontFamily.AR_NASKH -> R.string.ie_font_ar_naskh
        EditorFontFamily.AR_SANS -> R.string.ie_font_ar_sans
        EditorFontFamily.AR_MEDIUM -> R.string.ie_font_ar_medium
        EditorFontFamily.AR_LIGHT -> R.string.ie_font_ar_light
        EditorFontFamily.AR_CONDENSED -> R.string.ie_font_ar_condensed
        EditorFontFamily.AR_BLACK -> R.string.ie_font_ar_black
        else -> R.string.ie_font_default
    }
)

@Composable
private fun FontChip(selected: Boolean, label: String, family: EditorFontFamily, onClick: () -> Unit) {
    // كل خط يُعرض باسمه بشكله الحقيقي
    val fontFamily = remember(family) {
        FontFamily(org.openfilemanager.app.imageedit.engine.EditorFonts.resolve(family))
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontFamily = fontFamily, maxLines = 1, softWrap = false) }
    )
}

@Composable
private fun TextFontSection(vm: ImageEditorViewModel, sel: EditLayer.Text) {
    // كان صف الخطوط يلتف حرفاً حرفاً لضيق العرض؛ الآن كل مجموعة تمرَّر أفقياً
    PanelLabel(stringResource(R.string.ie_font_group_arabic))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorFontFamily.ARABIC_LIST.forEach { f ->
            FontChip(sel.fontFamily == f, arabicFontName(f), f) {
                vm.updateSelectedText { it.copy(fontFamily = f) }
            }
        }
    }
    PanelLabel(stringResource(R.string.ie_font_group_english))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorFontFamily.ENGLISH_LIST.forEach { f ->
            FontChip(sel.fontFamily == f, englishFontName(f), f) {
                vm.updateSelectedText { it.copy(fontFamily = f) }
            }
        }
    }
}

@Composable
private fun TextSlider(
    vm: ImageEditorViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    apply: (EditLayer.Text, Float) -> EditLayer.Text
) {
    LabeledSlider(label, value, range) { v, done ->
        vm.beginSliderCommit()
        vm.updateSelectedText(record = false) { apply(it, v) }
        if (done) vm.endSliderCommit()
    }
}

@Composable
private fun TextStyleSection(vm: ImageEditorViewModel, sel: EditLayer.Text, state: EditorUiState) {
    // عريض / مائل / تسطير / يتوسطه خط
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = sel.bold, onClick = { vm.updateSelectedText { it.copy(bold = !it.bold) } },
            label = { Icon(Icons.Outlined.FormatBold, stringResource(R.string.ie_bold)) })
        FilterChip(selected = sel.italic, onClick = { vm.updateSelectedText { it.copy(italic = !it.italic) } },
            label = { Icon(Icons.Outlined.FormatItalic, stringResource(R.string.ie_italic)) })
        FilterChip(selected = sel.underline, onClick = { vm.updateSelectedText { it.copy(underline = !it.underline) } },
            label = { Icon(Icons.Outlined.FormatUnderlined, stringResource(R.string.ie_underline)) })
        FilterChip(selected = sel.strike, onClick = { vm.updateSelectedText { it.copy(strike = !it.strike) } },
            label = { Icon(Icons.Outlined.StrikethroughS, stringResource(R.string.ie_strike)) })
    }
    // المحاذاة
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = sel.alignment == TextAlignment.START,
            onClick = { vm.updateSelectedText { it.copy(alignment = TextAlignment.START) } },
            label = { Icon(Icons.AutoMirrored.Outlined.FormatAlignLeft, stringResource(R.string.ie_align_start)) })
        FilterChip(selected = sel.alignment == TextAlignment.CENTER,
            onClick = { vm.updateSelectedText { it.copy(alignment = TextAlignment.CENTER) } },
            label = { Icon(Icons.Outlined.FormatAlignCenter, stringResource(R.string.ie_align_center)) })
        FilterChip(selected = sel.alignment == TextAlignment.END,
            onClick = { vm.updateSelectedText { it.copy(alignment = TextAlignment.END) } },
            label = { Icon(Icons.AutoMirrored.Outlined.FormatAlignRight, stringResource(R.string.ie_align_end)) })
    }
    TextSlider(vm, stringResource(R.string.ie_font_size), sel.fontSizePx * state.previewScale, 10f..140f) { t, v ->
        t.copy(fontSizePx = v / state.previewScale)
    }
    TextSlider(vm, stringResource(R.string.ie_text_width), sel.boxWidthFrac * 100f, 20f..100f) { t, v ->
        t.copy(boxWidthFrac = v / 100f)
    }
    TextSlider(vm, stringResource(R.string.ie_line_spacing), sel.lineSpacing * 100f, 70f..250f) { t, v ->
        t.copy(lineSpacing = v / 100f)
    }
    TextSlider(vm, stringResource(R.string.ie_letter_spacing), sel.letterSpacing * 100f, 0f..40f) { t, v ->
        t.copy(letterSpacing = v / 100f)
    }
}

@Composable
private fun TextEffectsSection(vm: ImageEditorViewModel, sel: EditLayer.Text, state: EditorUiState) {
    // ---- الحدّ
    FilterChip(selected = sel.outline, onClick = { vm.updateSelectedText { it.copy(outline = !it.outline) } },
        label = { Text(stringResource(R.string.ie_outline)) })
    if (sel.outline) {
        PanelLabel(stringResource(R.string.ie_outline_color))
        EditorColorRow(sel.outlineColor ?: 0xFF000000) { c -> vm.updateSelectedText { it.copy(outlineColor = c) } }
        val widthScreen = (if (sel.outlineWidthPx > 0f) sel.outlineWidthPx else sel.fontSizePx * 0.08f) * state.previewScale
        TextSlider(vm, stringResource(R.string.ie_outline_width), widthScreen, 1f..24f) { t, v ->
            t.copy(outlineWidthPx = v / state.previewScale)
        }
    }
    // ---- الظل
    FilterChip(selected = sel.shadow, onClick = { vm.updateSelectedText { it.copy(shadow = !it.shadow) } },
        label = { Text(stringResource(R.string.ie_shadow)) })
    if (sel.shadow) {
        PanelLabel(stringResource(R.string.ie_shadow_color))
        EditorColorRow(sel.shadowColor or 0xFF000000L) { c ->
            vm.updateSelectedText { it.copy(shadowColor = (c and 0x00FFFFFFL) or (it.shadowColor and 0xFF000000L)) }
        }
    }
    // ---- البطاقة (خلفية خلف النص)
    FilterChip(selected = sel.bgColor != null,
        onClick = { vm.updateSelectedText { it.copy(bgColor = if (it.bgColor == null) 0xCC000000 else null) } },
        label = { Text(stringResource(R.string.ie_background)) })
    val bg = sel.bgColor
    if (bg != null) {
        PanelLabel(stringResource(R.string.ie_bg_color))
        EditorColorRow(bg or 0xFF000000L) { c ->
            vm.updateSelectedText { it.copy(bgColor = (c and 0x00FFFFFFL) or ((it.bgColor ?: 0xCC000000) and 0xFF000000L)) }
        }
        val alphaPct = ((bg ushr 24) and 0xFFL).toFloat() / 255f * 100f
        TextSlider(vm, stringResource(R.string.ie_card_opacity), alphaPct, 5f..100f) { t, v ->
            val a = (v / 100f * 255f).toLong().coerceIn(0L, 255L)
            t.copy(bgColor = ((t.bgColor ?: 0xCC000000) and 0x00FFFFFFL) or (a shl 24))
        }
    }
}

/** صف الأزرار المشترك للطبقة المحددة: شفافية/تقديم/تأخير/حذف. */
@Composable
private fun LayerCommonRow(vm: ImageEditorViewModel, state: EditorUiState) {
    val sel = state.selected ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledSlider(
            stringResource(R.string.ie_opacity),
            sel.opacity * 100f,
            5f..100f
        ) { v, done ->
            vm.beginSliderCommit()
            vm.setSelectedOpacity(v / 100f)
            if (done) vm.endSliderCommit()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { vm.reorderSelected(front = true) }) {
                Icon(Icons.Outlined.FlipToFront, stringResource(R.string.ie_to_front))
            }
            IconButton(onClick = { vm.reorderSelected(front = false) }) {
                Icon(Icons.Outlined.FlipToBack, stringResource(R.string.ie_to_back))
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { vm.deleteSelectedLayer() }) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.ie_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ---------------------------------------------------------------------
// القصّ

@Composable
private fun CropToolPanel(
    vm: ImageEditorViewModel,
    state: EditorUiState,
    cropRect: FloatRect?,
    onCropRectChange: (FloatRect?) -> Unit
) {
    val (pw, ph) = state.transform.preCropSize(state.docW, state.docH)
    val full = FloatRect.xywh(0f, 0f, pw, ph)
    val t = state.transform
    PanelColumn {
        PanelLabel(stringResource(R.string.ie_crop_ratio))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = cropRect == null || cropRect == full,
                onClick = { onCropRectChange(full) },
                label = { Text(stringResource(R.string.ie_crop_free)) })
            CROP_ASPECTS.forEach { (aspect, label) ->
                FilterChip(
                    selected = cropRect?.let { r ->
                        kotlin.math.abs(r.width / r.height - aspect) < 0.01f
                    } == true,
                    onClick = { onCropRectChange(CropMath.centeredAspectCrop(pw, ph, aspect)) },
                    label = { Text(stringResource(label)) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { vm.rotateQuarter(-1) }) {
                Icon(Icons.Outlined.RotateLeft, stringResource(R.string.ie_rotate_ccw))
            }
            IconButton(onClick = { vm.rotateQuarter(1) }) {
                Icon(Icons.Outlined.RotateRight, stringResource(R.string.ie_rotate_cw))
            }
            IconButton(onClick = { vm.flipTransform(horizontal = true) }) {
                Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.ie_flip_h))
            }
            IconButton(onClick = { vm.flipTransform(horizontal = false) }) {
                Icon(Icons.Outlined.SwapVert, stringResource(R.string.ie_flip_v))
            }
        }
        // تدوير دقيق: معاينة بلا تسجيل أثناء السحب، وإدخال واحد عند رفع الإصبع
        LabeledSlider(stringResource(R.string.ie_straighten), t.freeRotation, -45f..45f) { v, done ->
            if (done) vm.setFreeRotation(v) else vm.setFreeRotationLive(v)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onCropRectChange(full) }) { Text(stringResource(R.string.ie_crop_reset)) }
            FilledTonalButton(onClick = {
                vm.applyCrop(cropRect?.takeIf { it != full })
                vm.selectTool(org.openfilemanager.app.imageedit.model.EditorTool.CROP)
            }) { Text(stringResource(R.string.ie_crop_apply)) }
        }
        PanelLabel(stringResource(R.string.ie_crop_hint))
    }
}

private val CROP_ASPECTS = listOf(
    1f to R.string.ie_crop_1_1, (4f / 3f) to R.string.ie_crop_4_3, (3f / 4f) to R.string.ie_crop_3_4,
    (16f / 9f) to R.string.ie_crop_16_9, (9f / 16f) to R.string.ie_crop_9_16,
    (3f / 2f) to R.string.ie_crop_3_2, (2f / 3f) to R.string.ie_crop_2_3
)

// ---------------------------------------------------------------------
// الصور المضافة

@Composable
private fun ImageToolPanel(vm: ImageEditorViewModel, state: EditorUiState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.addImageLayer(uri.toString())
    }
    val sel = state.selected as? EditLayer.Image
    PanelColumn {
        FilledTonalButton(onClick = { picker.launch("image/*") }) {
            Icon(Icons.Outlined.AddPhotoAlternate, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ie_add_image))
        }
        if (sel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { vm.flipSelectedImage(horizontal = true) }) {
                    Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.ie_flip_h))
                }
                IconButton(onClick = { vm.flipSelectedImage(horizontal = false) }) {
                    Icon(Icons.Outlined.SwapVert, stringResource(R.string.ie_flip_v))
                }
            }
            LayerCommonRow(vm, state)
        } else {
            PanelLabel(stringResource(R.string.ie_image_hint))
        }
    }
}

// ---------------------------------------------------------------------
// الرسم الحر

@Composable
private fun DrawToolPanel(vm: ImageEditorViewModel, state: EditorUiState) {
    val sel = state.selected as? EditLayer.Stroke
    PanelColumn {
        FilterChip(
            selected = state.drawEraser,
            onClick = { vm.setDrawSettings(eraser = !state.drawEraser) },
            label = { Text(stringResource(R.string.ie_eraser_strokes)) },
            leadingIcon = { Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp)) }
        )
        if (!state.drawEraser) {
            PanelLabel(stringResource(R.string.ie_brush_style))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrushStyle.entries.forEach { style ->
                    FilterChip(
                        selected = state.drawStyle == style,
                        onClick = { vm.setDrawSettings(style = style) },
                        label = { Text(stringResource(BRUSH_STYLE_LABEL[style] ?: R.string.ie_brush_pen)) },
                        leadingIcon = {
                            Icon(BRUSH_STYLE_ICON[style] ?: Icons.Outlined.Edit, null, Modifier.size(18.dp))
                        }
                    )
                }
            }
            PanelLabel(stringResource(R.string.ie_color))
            EditorColorRow(state.drawColor) { c -> vm.setDrawSettings(color = c) }
            LabeledSlider(stringResource(R.string.ie_brush_width), state.drawWidthScreenPx, 2f..60f) { v, _ ->
                vm.setDrawSettings(widthScreenPx = v)
            }
        } else {
            PanelLabel(stringResource(R.string.ie_eraser_hint))
        }
        if (sel != null) LayerCommonRow(vm, state)
    }
}

private val BRUSH_STYLE_LABEL = mapOf(
    BrushStyle.PEN to R.string.ie_brush_pen,
    BrushStyle.MARKER to R.string.ie_brush_marker,
    BrushStyle.NEON to R.string.ie_brush_neon
)

private val BRUSH_STYLE_ICON = mapOf(
    BrushStyle.PEN to Icons.Outlined.Edit,
    BrushStyle.MARKER to Icons.Outlined.Brush,
    BrushStyle.NEON to Icons.Outlined.AutoAwesome
)

// ---------------------------------------------------------------------
// الأشكال — أيقونات هندسية حقيقية (تُرسم بنفس مسارات المحرّك) بدل الأسماء النصية

private val SHAPE_ORDER = listOf(
    ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW, ShapeKind.BLOCK_ARROW,
    ShapeKind.RECT, ShapeKind.SQUARE, ShapeKind.ROUNDED_RECT, ShapeKind.CIRCLE, ShapeKind.ELLIPSE,
    ShapeKind.TRIANGLE, ShapeKind.RIGHT_TRIANGLE, ShapeKind.DIAMOND, ShapeKind.PENTAGON,
    ShapeKind.HEXAGON, ShapeKind.STAR, ShapeKind.HEART, ShapeKind.PLUS, ShapeKind.CHEVRON,
    ShapeKind.BUBBLE
)

private fun shapeLabel(kind: ShapeKind): Int = when (kind) {
    ShapeKind.LINE -> R.string.ie_shape_line
    ShapeKind.ARROW -> R.string.ie_shape_arrow
    ShapeKind.DOUBLE_ARROW -> R.string.ie_shape_double_arrow
    ShapeKind.BLOCK_ARROW -> R.string.ie_shape_block_arrow
    ShapeKind.RECT -> R.string.ie_shape_rect
    ShapeKind.SQUARE -> R.string.ie_shape_square
    ShapeKind.ROUNDED_RECT -> R.string.ie_shape_rounded_rect
    ShapeKind.CIRCLE -> R.string.ie_shape_circle
    ShapeKind.ELLIPSE -> R.string.ie_shape_ellipse
    ShapeKind.TRIANGLE -> R.string.ie_shape_triangle
    ShapeKind.RIGHT_TRIANGLE -> R.string.ie_shape_right_triangle
    ShapeKind.DIAMOND -> R.string.ie_shape_diamond
    ShapeKind.PENTAGON -> R.string.ie_shape_pentagon
    ShapeKind.HEXAGON -> R.string.ie_shape_hexagon
    ShapeKind.STAR -> R.string.ie_shape_star
    ShapeKind.HEART -> R.string.ie_shape_heart
    ShapeKind.PLUS -> R.string.ie_shape_plus
    ShapeKind.CHEVRON -> R.string.ie_shape_chevron
    ShapeKind.BUBBLE -> R.string.ie_shape_bubble
}

/** أيقونة شكل مرسومة هندسياً. */
@Composable
private fun ShapeIcon(kind: ShapeKind, tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    val argb = tint.toArgb()
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val pad = w * 0.14f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.085f
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            fun head(x1: Float, y1: Float, x2: Float, y2: Float) {
                val ang = kotlin.math.atan2(y2 - y1, x2 - x1)
                val len = w * 0.26f
                val sp = Math.toRadians(28.0)
                nc.drawLine(x2, y2, x2 - len * kotlin.math.cos(ang - sp).toFloat(), y2 - len * kotlin.math.sin(ang - sp).toFloat(), paint)
                nc.drawLine(x2, y2, x2 - len * kotlin.math.cos(ang + sp).toFloat(), y2 - len * kotlin.math.sin(ang + sp).toFloat(), paint)
            }
            when (kind) {
                ShapeKind.LINE -> nc.drawLine(pad, h - pad, w - pad, pad, paint)
                ShapeKind.ARROW -> {
                    nc.drawLine(pad, h - pad, w - pad, pad, paint)
                    head(pad, h - pad, w - pad, pad)
                }
                ShapeKind.DOUBLE_ARROW -> {
                    nc.drawLine(pad, h - pad, w - pad, pad, paint)
                    head(pad, h - pad, w - pad, pad)
                    head(w - pad, pad, pad, h - pad)
                }
                ShapeKind.BUBBLE -> {
                    val r = RectF(pad, pad, w - pad, h * 0.70f)
                    nc.drawRoundRect(r, w * 0.2f, w * 0.2f, paint)
                    val tail = android.graphics.Path().apply {
                        moveTo(w * 0.32f, h * 0.70f); lineTo(w * 0.22f, h - pad); lineTo(w * 0.5f, h * 0.70f)
                    }
                    nc.drawPath(tail, paint)
                }
                ShapeKind.RECT, ShapeKind.ELLIPSE ->
                    ImageRenderer.shapePath(kind, pad, h * 0.26f, w - pad, h * 0.74f)?.let { nc.drawPath(it, paint) }
                else ->
                    ImageRenderer.shapePath(kind, pad, pad, w - pad, h - pad)?.let { nc.drawPath(it, paint) }
            }
        }
    }
}

@Composable
private fun ShapesToolPanel(vm: ImageEditorViewModel, state: EditorUiState) {
    val sel = state.selected as? EditLayer.Shape
    var bubbleText by remember(sel?.id) { mutableStateOf(sel?.bubbleText ?: "") }
    val activeKind = sel?.kind ?: state.shapeKind
    val activeStroke = sel?.strokeColor ?: state.shapeStrokeColor
    val activeFill = if (sel != null) sel.fillColor else state.shapeFillColor
    val activeWidth = (sel?.strokeWidthPx?.let { it * state.previewScale }) ?: state.shapeWidthScreenPx

    PanelColumn {
        // اختيار النوع: يُغيّر نوع الشكل المحدد إن وُجد، ويضبط نوع الأشكال الجديدة دائماً
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SHAPE_ORDER.forEach { kind ->
                val chosen = activeKind == kind
                val tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                val desc = stringResource(shapeLabel(kind))
                Box(
                    Modifier
                        .size(48.dp)
                        .background(
                            if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            BorderStroke(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(12.dp)
                        )
                        .semantics { contentDescription = desc }
                        .clickable {
                            vm.setShapeSettings(kind = kind)
                            if (sel != null) vm.updateSelectedShape { it.copy(kind = kind) }
                        },
                    contentAlignment = Alignment.Center
                ) { ShapeIcon(kind, tint) }
            }
        }
        PanelLabel(stringResource(R.string.ie_color))
        EditorColorRow(activeStroke) { c ->
            vm.setShapeSettings(strokeColor = c)
            if (sel != null) vm.updateSelectedShape { it.copy(strokeColor = c) }
        }
        if (!activeKind.isLinear) {
            FilterChip(
                selected = activeFill != null,
                onClick = {
                    if (activeFill == null) {
                        vm.setShapeSettings(fillColor = 0xFFFFFFFF)
                        if (sel != null) vm.updateSelectedShape { it.copy(fillColor = 0xFFFFFFFF) }
                    } else {
                        vm.setShapeSettings(clearFill = true)
                        if (sel != null) vm.updateSelectedShape { it.copy(fillColor = null) }
                    }
                },
                label = { Text(stringResource(R.string.ie_shape_fill)) }
            )
            if (activeFill != null) {
                EditorColorRow(activeFill) { c ->
                    vm.setShapeSettings(fillColor = c)
                    if (sel != null) vm.updateSelectedShape { it.copy(fillColor = c) }
                }
            }
        }
        LabeledSlider(stringResource(R.string.ie_shape_width), activeWidth, 2f..40f) { v, done ->
            vm.setShapeSettings(widthScreenPx = v)
            if (sel != null) {
                vm.beginSliderCommit()
                vm.updateSelectedShape(record = false) { it.copy(strokeWidthPx = v / state.previewScale) }
                if (done) vm.endSliderCommit()
            }
        }
        if (sel != null) {
            if (sel.kind == ShapeKind.BUBBLE) {
                OutlinedTextField(
                    value = bubbleText,
                    onValueChange = { v ->
                        bubbleText = v
                        vm.updateSelectedShape { it.copy(bubbleText = v) }
                    },
                    label = { Text(stringResource(R.string.ie_bubble_text)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
            }
            LayerCommonRow(vm, state)
        } else {
            PanelLabel(stringResource(R.string.ie_shapes_hint))
        }
    }
}

// ---------------------------------------------------------------------
// التدوير والقلب

@Composable
private fun RotateToolPanel(vm: ImageEditorViewModel, state: EditorUiState) {
    PanelColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.rotateQuarter(-1) }) { Text(stringResource(R.string.ie_rotate_ccw)) }
            FilledTonalButton(onClick = { vm.rotateQuarter(1) }) { Text(stringResource(R.string.ie_rotate_cw)) }
            FilledTonalButton(onClick = { vm.rotateQuarter(2) }) { Text(stringResource(R.string.ie_rotate_180)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.flipTransform(horizontal = true) }) {
                Icon(Icons.Outlined.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ie_flip_h))
            }
            FilledTonalButton(onClick = { vm.flipTransform(horizontal = false) }) {
                Icon(Icons.Outlined.SwapVert, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ie_flip_v))
            }
        }
        LabeledSlider(stringResource(R.string.ie_free_rotation), state.transform.freeRotation, -45f..45f) { v, done ->
            vm.beginSliderCommit()
            vm.setFreeRotationLive(v)
            if (done) vm.endSliderCommit()
        }
        FilledTonalButton(onClick = { vm.resetTransform() }) { Text(stringResource(R.string.ie_reset)) }
    }
}
