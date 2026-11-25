package org.openfilemanager.app.imageedit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.ImageEditorViewModel
import org.openfilemanager.app.imageedit.ImageEditorViewModel.EditorEvent
import org.openfilemanager.app.imageedit.engine.ImageExporter
import org.openfilemanager.app.imageedit.model.EditorTool
import org.openfilemanager.app.imageedit.model.ExportFormat
import org.openfilemanager.app.imageedit.model.isEditableImage
import org.openfilemanager.app.ui.LocalSnack
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.components.SnackKind
import org.openfilemanager.app.ui.components.show
import org.openfilemanager.core.imageedit.FloatRect
import kotlin.math.roundToInt

/**
 * شاشة محرّر الصور: شريط علوي (رجوع/تراجع/إعادة/حفظ) + قماش + لوحة خيارات
 * الأداة + شريط أدوات أيقوني أفقي قابل للتمرير بكل الأدوات — لا شاشة منفصلة
 * لكل أداة. تُفتح حصراً من قائمة خيارات الصورة في العارض.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ImageEditorScreen(path: String, onBack: () -> Unit, onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as android.app.Application
    val vm: ImageEditorViewModel = viewModel(key = "imageeditor:$path") {
        ImageEditorViewModel(app, path)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val liveCut by vm.liveCut.collectAsStateWithLifecycle()
    val snack = LocalSnack.current

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is EditorEvent.Saved -> {
                    snack.show(ctx.getString(R.string.ie_saved_to, File(e.path).name), SnackKind.SUCCESS)
                    onSaved()
                }
                is EditorEvent.Failed -> snack.show(ctx.getString(e.res), SnackKind.ERROR)
                is EditorEvent.Message -> snack.show(ctx.getString(e.res), SnackKind.INFO)
            }
        }
    }

    var showDiscard by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    BackHandler { if (state.dirty && !state.saving) showDiscard = true else onBack() }

    // منطقة القصّ محلية للشاشة حتى تطبقها بلوحة القصّ والقماش معاً
    var cropRect by remember(state.tool, state.transform) {
        mutableStateOf(
            if (state.tool == EditorTool.CROP) {
                state.transform.crop ?: fullCropRectOf(state)
            } else null
        )
    }

    // (تصحيح) imePadding على الجذر كله (لا على اللوحة وحدها): القماش يأخذ ما يتبقى فوق لوحة
    // المفاتيح، وارتفاع لوحة الأداة يُقيَّد بنسبة من الشاشة فلا تبتلع الصورة أبداً.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().imePadding()
    ) {
    val imeVisible = WindowInsets.isImeVisible
    val panelMax = if (imeVisible) maxHeight * 0.36f else minOf(300.dp, maxHeight * 0.45f)
    // Icons/text without explicit tint inherit LocalContentColor (black here) -> invisible on dark bg
    CompositionLocalProvider(
        LocalPanelMaxHeight provides panelMax,
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground
    ) {
    Column(Modifier.fillMaxSize()) {
        // ---- الشريط العلوي
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack, enabled = !state.saving) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back))
            }
            Text(
                BidiText.wrap(state.name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
    // (تحسين) مقارنة قبل/بعد: عرض الأصل طوال الضغط على الزر بلا لمسة إضافية
            val comparePress = remember { MutableInteractionSource() }
            val comparing by comparePress.collectIsPressedAsState()
            LaunchedEffect(comparing) { vm.setShowOriginal(comparing) }
            IconButton(
                onClick = {}, interactionSource = comparePress,
                enabled = state.dirty && !state.saving
            ) {
                Icon(Icons.Outlined.Compare, stringResource(R.string.ie_compare))
            }
            IconButton({ vm.undo() }, enabled = state.canUndo && !state.saving) {
                Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.ui_undo))
            }
            IconButton({ vm.redo() }, enabled = state.canRedo && !state.saving) {
                Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.ie_redo))
            }
            IconButton(
                { if (state.dirty) showSaveSheet = true },
                enabled = state.dirty && !state.saving
            ) {
                if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Save, stringResource(R.string.ui_save))
            }
        }

        // ---- القماش
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.ie_load_failed),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onBack) { Text(stringResource(R.string.ui_back)) }
                    }
                }
                else -> EditorCanvas(
                    state = state,
                    preview = preview,
                    controller = vm,
                    cropRect = cropRect,
                    onCropRectChange = { cropRect = it },
                    liveCut = liveCut
                )
            }
        }

        // ---- لوحة خيارات الأداة
        AnimatedVisibility(visible = state.tool != EditorTool.NONE) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EditorToolPanel(vm, state, cropRect, onCropRectChange = { cropRect = it })
            }
        }

        // ---- شريط الأدوات الأفقي القابل للتمرير
        if (!imeVisible) Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
        ) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(TOOLBAR_ITEMS) { (tool, icon, labelRes, shortRes) ->
                    val selected = state.tool == tool
                    val tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    // (تحسين) أيقونة وعنوانها تحته: يعرف المستخدم الأداة دون تخمين معنى الأيقونة
                    Column(
                        Modifier
                            .widthIn(min = 60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { vm.selectTool(tool) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, stringResource(labelRes), tint = tint)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(shortRes),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            maxLines = 1,
                            color = tint
                        )
                    }
                }
            }
        }
    }
    }
    }

    // ------------------------------------------------------------------
    // الحوارات

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.ie_discard_title)) },
            text = { Text(stringResource(R.string.ie_discard_body)) },
            confirmButton = {
                Row {
                    TextButton({ showDiscard = false; onBack() }) {
                        Text(stringResource(R.string.ie_discard), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton({ showDiscard = false; showSaveSheet = true }) {
                        Text(stringResource(R.string.ui_save))
                    }
                }
            },
            dismissButton = {
                TextButton({ showDiscard = false }) { Text(stringResource(R.string.ie_keep_editing)) }
            }
        )
    }

    if (showSaveSheet) {
        ActionSheet(
            BidiText.wrap(state.name),
            listOf(
                SheetAction(Icons.Outlined.Save, stringResource(R.string.ie_save_as_new)) {
                    showSaveDialog = true
                },
                SheetAction(
                    Icons.Outlined.Loop, stringResource(R.string.ie_replace_original),
                    destructive = true, dividerBefore = true
                ) {
                    showReplaceConfirm = true
                }
            )
        ) { showSaveSheet = false }
    }

    if (showSaveDialog) {
        SaveDialog(
            defaultName = ImageExporter.defaultCopyName(path),
            hasCut = state.cutOps.isNotEmpty(),
            fullSize = exportSizeOf(state, 1f),
            onSave = { name, format, quality, scale ->
                showSaveDialog = false
                vm.saveCopy(name, format, quality, scale)
            },
            onDismiss = { showSaveDialog = false }
        )
    }

    if (showReplaceConfirm) {
        val ext = path.substringAfterLast('.', "").lowercase()
        // الصورة المقصوصة فيها شفافية: لا يصح استبدال JPEG بها (تصير الشفافية سوداء)
        val replaceable = replaceableFormats.contains(ext) &&
            !(state.cutOps.isNotEmpty() && (ext == "jpg" || ext == "jpeg"))
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text(stringResource(R.string.ie_replace_original)) },
            text = {
                Text(
                    if (replaceable) stringResource(R.string.ie_replace_warning)
                    else stringResource(R.string.ie_replace_unsupported)
                )
            },
            confirmButton = {
                if (replaceable) TextButton({
                    showReplaceConfirm = false
                    vm.replaceOriginal(90)
                }) { Text(stringResource(R.string.ie_replace_confirm)) }
            },
            dismissButton = { TextButton({ showReplaceConfirm = false }) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
}

private val replaceableFormats = setOf("jpg", "jpeg", "png", "webp")

/** أبعاد الصورة الناتجة (بعد القصّ والتدوير) عند نسبة تصغير [scale]. */
private fun exportSizeOf(state: ImageEditorViewModel.EditorUiState, scale: Float): Pair<Int, Int> {
    val (pw, ph) = state.transform.preCropSize(state.docW, state.docH)
    val crop = state.transform.crop
    val w = (crop?.width ?: pw) * scale
    val h = (crop?.height ?: ph) * scale
    return maxOf(1, w.roundToInt()) to maxOf(1, h.roundToInt())
}

private fun fullCropRectOf(state: ImageEditorViewModel.EditorUiState): FloatRect {
    val (w, h) = state.transform.preCropSize(state.docW, state.docH)
    return FloatRect.xywh(0f, 0f, w, h)
}

/** الأداة، أيقونتها، عنوانها الوصفي (للمساعدة الصوتية) وعنوانها القصير تحت الأيقونة. */
private data class ToolbarItem(
    val tool: EditorTool,
    val icon: ImageVector,
    val labelRes: Int,
    val shortRes: Int
)

private val TOOLBAR_ITEMS: List<ToolbarItem> = listOf(
    ToolbarItem(EditorTool.ADJUST, Icons.Outlined.Tune, R.string.ie_tool_adjust, R.string.ie_short_adjust),
    ToolbarItem(EditorTool.CROP, Icons.Outlined.Crop, R.string.ie_tool_crop, R.string.ie_short_crop),
    ToolbarItem(EditorTool.CUT, Icons.Outlined.ContentCut, R.string.ie_tool_cut, R.string.ie_short_cut),
    ToolbarItem(EditorTool.ROTATE, Icons.Outlined.RotateRight, R.string.ie_tool_rotate, R.string.ie_short_rotate),
    ToolbarItem(EditorTool.TEXT, Icons.Outlined.TextFields, R.string.ie_tool_text, R.string.ie_short_text),
    ToolbarItem(EditorTool.DRAW, Icons.Outlined.Brush, R.string.ie_tool_draw, R.string.ie_short_draw),
    ToolbarItem(EditorTool.SHAPES, Icons.Outlined.Category, R.string.ie_tool_shapes, R.string.ie_short_shapes),
    ToolbarItem(EditorTool.IMAGE, Icons.Outlined.AddPhotoAlternate, R.string.ie_tool_image, R.string.ie_short_image)
)

/** حوار حفظ نسخة جديدة: الاسم والصيغة وجودة الترميز. */
@Composable
private fun SaveDialog(
    defaultName: String,
    hasCut: Boolean = false,
    fullSize: Pair<Int, Int> = 1 to 1,
    onSave: (String, ExportFormat, Int, Float) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var format by rememberSaveable { mutableStateOf((if (hasCut) ExportFormat.PNG else ExportFormat.JPEG).name) }
    var quality by rememberSaveable { mutableStateOf(90) }
    var sizeIdx by rememberSaveable { mutableStateOf(0) }
    val fmt = ExportFormat.valueOf(format)
    val scale = EXPORT_SCALES[sizeIdx]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ie_save_as_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ie_file_name)) },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { f ->
                        FilterChip(
                            selected = fmt == f,
                            onClick = { format = f.name },
                            label = {
                                Text(
                                    stringResource(
                                        when (f) {
                                            ExportFormat.JPEG -> R.string.ie_format_jpeg
                                            ExportFormat.PNG -> R.string.ie_format_png
                                            ExportFormat.WEBP -> R.string.ie_format_webp
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
                if (hasCut && fmt == ExportFormat.JPEG) {
                    Text(
                        stringResource(R.string.ie_cut_jpeg_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (fmt != ExportFormat.PNG) {
                    Text(
                        stringResource(R.string.ie_quality) + ": " + quality,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { quality = it.toInt() },
                        valueRange = 40f..100f
                    )
                }
                Text(
                    stringResource(R.string.ie_export_size),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXPORT_SCALES.forEachIndexed { i, s ->
                        FilterChip(
                            selected = sizeIdx == i,
                            onClick = { sizeIdx = i },
                            label = {
                                Text(
                                    if (i == 0) stringResource(R.string.ie_size_original)
                                    else stringResource(
                                        R.string.ie_size_result,
                                        (fullSize.first * s).toInt(), (fullSize.second * s).toInt()
                                    )
                                )
                            }
                        )
                    }
                }
                Text(
                    stringResource(R.string.ie_save_dir_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton({ onSave(name.ifEmpty { defaultName }, fmt, quality, scale) }) {
                Text(stringResource(R.string.ui_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

/** نسب تصغير حجم الملف عند الحفظ (تصغير الأبعاد بنفس النسبة). */
private val EXPORT_SCALES = listOf(1f, 0.75f, 0.5f, 0.25f)
