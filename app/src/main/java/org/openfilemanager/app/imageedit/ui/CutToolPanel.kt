package org.openfilemanager.app.imageedit.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CropDin
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Square
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.ImageEditorViewModel
import org.openfilemanager.app.imageedit.ImageEditorViewModel.EditorUiState
import org.openfilemanager.app.imageedit.model.CutMode
import org.openfilemanager.app.imageedit.model.CutOp
import org.openfilemanager.core.imageedit.CutShape
import kotlin.math.roundToInt

/**
 * لوحة القصّ: ستة أوضاع في صف واحد (قصّ حر، مسح منطقة، قصّ الخلفية، ممحاة، استعادة،
 * موزاييك) وزر «إزالة الخلفية تلقائياً» في المقدمة. تحت الأوضاع تظهر ضوابط الوضع
 * النشط فقط، وتحرّك الشرائح آخر عملية قصّ مباشرة بدل إضافة عملية جديدة.
 */
@Composable
fun CutToolPanel(vm: ImageEditorViewModel, state: EditorUiState) {
    val mode = state.cutMode
    val last = state.cutOps.lastOrNull()
    // القيم المعروضة = قيم آخر عملية قصّ إن كانت تقبل الضبط، وإلا الافتراضية للعملية القادمة
    val lastTol = (last as? CutOp.Wand)?.tolerance ?: (last as? CutOp.AutoBg)?.tolerance
    val lastEdge = (last as? CutOp.Wand)?.edgeStop ?: (last as? CutOp.AutoBg)?.edgeStop
    val lastFeather = when (last) {
        is CutOp.Keep, is CutOp.Erase, is CutOp.Wand, is CutOp.AutoBg -> last.feather
        else -> null
    }

    PanelColumn {
        FilledTonalButton(onClick = { vm.autoRemoveBackground() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.AutoFixHigh, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ie_cut_auto))
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CutModeChip(mode, CutMode.KEEP, Icons.Outlined.CropFree, R.string.ie_cut_keep, vm)
            CutModeChip(mode, CutMode.ERASE, Icons.Outlined.ContentCut, R.string.ie_cut_erase, vm)
            CutModeChip(mode, CutMode.WAND, Icons.Outlined.AutoFixHigh, R.string.ie_cut_wand, vm)
            CutModeChip(mode, CutMode.ERASER, Icons.Outlined.CleaningServices, R.string.ie_cut_eraser, vm)
            CutModeChip(mode, CutMode.RESTORE, Icons.Outlined.History, R.string.ie_cut_restore, vm)
            CutModeChip(mode, CutMode.MOSAIC, Icons.Outlined.BlurOn, R.string.ie_cut_mosaic, vm)
        }

        PanelLabel(
            stringResource(
                when (mode) {
                    CutMode.KEEP -> R.string.ie_cut_hint_keep
                    CutMode.ERASE -> R.string.ie_cut_hint_erase
                    CutMode.WAND -> R.string.ie_cut_hint_wand
                    CutMode.ERASER -> R.string.ie_cut_hint_eraser
                    CutMode.RESTORE -> R.string.ie_cut_hint_restore
                    CutMode.MOSAIC -> R.string.ie_cut_hint_mosaic
                }
            )
        )

        when (mode) {
            CutMode.KEEP, CutMode.ERASE -> {
                PanelLabel(stringResource(R.string.ie_cut_shape))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CutShapeChip(state.cutShape, CutShape.FREE, Icons.Outlined.Gesture, R.string.ie_cut_shape_free, vm)
                    CutShapeChip(state.cutShape, CutShape.RECT, Icons.Outlined.CropDin, R.string.ie_cut_shape_rect, vm)
                    CutShapeChip(state.cutShape, CutShape.SQUARE, Icons.Outlined.Square, R.string.ie_cut_shape_square, vm)
                    CutShapeChip(state.cutShape, CutShape.ELLIPSE, Icons.Outlined.Circle, R.string.ie_cut_shape_ellipse, vm)
                    CutShapeChip(state.cutShape, CutShape.CIRCLE, Icons.Outlined.RadioButtonUnchecked, R.string.ie_cut_shape_circle, vm)
                }
                if (state.cutShape == CutShape.CIRCLE) {
                    PanelLabel(stringResource(R.string.ie_cut_shape_circle_hint))
                }
                if (state.cutShape != CutShape.FREE) {
                    PanelLabel(stringResource(R.string.ie_cut_hint_shape))
                }
                CutSlider(vm,
                    stringResource(R.string.ie_cut_feather), lastFeather ?: state.cutFeather, 0..100
                ) { v -> vm.setCutTuning(feather = v) }
            }
            CutMode.WAND -> {
                CutSlider(vm, stringResource(R.string.ie_cut_tolerance), lastTol ?: state.cutTolerance, 0..100) { v ->
                    vm.setCutTuning(tolerance = v)
                }
                CutSlider(vm, stringResource(R.string.ie_cut_edge), lastEdge ?: state.cutEdge, 0..100) { v ->
                    vm.setCutTuning(edgeStop = v)
                }
                CutSlider(vm,
                    stringResource(R.string.ie_cut_feather), lastFeather ?: state.cutFeather, 0..100
                ) { v -> vm.setCutTuning(feather = v) }
                if (lastTol != null) PanelLabel(stringResource(R.string.ie_cut_retune_hint))
            }
            CutMode.ERASER, CutMode.RESTORE -> {
                LabeledSlider(stringResource(R.string.ie_cut_brush_size), state.cutBrushScreenPx, 6f..240f) { v, _ ->
                    vm.setCutBrush(sizeScreenPx = v)
                }
                CutSlider(vm, stringResource(R.string.ie_cut_hardness), state.cutBrushHardness, 0..100) { v ->
                    vm.setCutBrush(hardness = v)
                }
            }
            CutMode.MOSAIC -> {
                LabeledSlider(stringResource(R.string.ie_cut_brush_size), state.cutBrushScreenPx, 10f..300f) { v, _ ->
                    vm.setCutBrush(sizeScreenPx = v)
                }
            }
        }

        if (state.cutOps.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { vm.removeLastCut() }) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ie_cut_undo_last))
                }
                TextButton(onClick = { vm.resetCut() }) {
                    Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ie_cut_reset), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun CutModeChip(
    current: CutMode, mode: CutMode, icon: ImageVector, label: Int, vm: ImageEditorViewModel
) {
    FilterChip(
        selected = current == mode,
        onClick = { vm.setCutSettings(mode = mode) },
        label = { Text(stringResource(label), maxLines = 1) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }
    )
}

@Composable
private fun CutShapeChip(
    current: CutShape, shape: CutShape, icon: ImageVector, label: Int, vm: ImageEditorViewModel
) {
    FilterChip(
        selected = current == shape,
        onClick = { vm.setCutSettings(shape = shape) },
        label = { Text(stringResource(label), maxLines = 1) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }
    )
}

/** منزلق قصّ بقيمة صحيحة: سحبة واحدة = إدخال تاريخ واحد (يُثبَّت مع نهاية السحب). */
@Composable
private fun CutSlider(
    vm: ImageEditorViewModel, label: String, value: Int, range: IntRange, onCommit: (Int) -> Unit
) {
    LabeledSlider(label, value.toFloat(), range.first.toFloat()..range.last.toFloat()) { v, done ->
        vm.beginSliderCommit()
        onCommit(v.roundToInt())
        if (done) vm.endSliderCommit()
    }
}
