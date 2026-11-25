package org.openfilemanager.app.imageedit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openfilemanager.app.R
import org.openfilemanager.app.imageedit.ImageEditorViewModel
import org.openfilemanager.app.imageedit.ImageEditorViewModel.EditorUiState
import org.openfilemanager.core.imageedit.Adjust
import org.openfilemanager.core.imageedit.FilterPreset

/**
 * لوحة التعديل: شريط فلاتر بمعاينة حيّة على نسخة مصغّرة من الصورة، وتحته شرائح
 * الإضاءة/التباين/التشبّع/الدفء والتظليل الحوافي. كلها قيم [Adjust] لا تُطبَّق على
 * البكسلات إلا عند الحفظ، فتبقى قابلة للتعديل والتراجع في أي لحظة.
 */
@Composable
fun AdjustToolPanel(vm: ImageEditorViewModel, state: EditorUiState) {
    val thumb by vm.thumbBase.collectAsStateWithLifecycle()
    val adj = state.adjust
    val bitmap = thumb
    PanelColumn {
        PanelLabel(stringResource(R.string.ie_adjust_hint))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterPreset.entries.forEach { preset ->
                val selected = if (preset == FilterPreset.NONE) adj.isIdentity else preset.adjust == adj
                val label = stringResource(
                    when (preset) {
                        FilterPreset.NONE -> R.string.ie_filter_none
                        FilterPreset.VIVID -> R.string.ie_filter_vivid
                        FilterPreset.WARM -> R.string.ie_filter_warm
                        FilterPreset.COOL -> R.string.ie_filter_cool
                        FilterPreset.SEPIA -> R.string.ie_filter_sepia
                        FilterPreset.MONO -> R.string.ie_filter_mono
                        FilterPreset.NOIR -> R.string.ie_filter_noir
                        FilterPreset.FADE -> R.string.ie_filter_fade
                        FilterPreset.VINTAGE -> R.string.ie_filter_vintage
                    }
                )
                Column(
                    Modifier
                        .width(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { vm.applyFilter(preset) }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = label,
                            contentScale = ContentScale.Crop,
                            colorFilter = if (preset == FilterPreset.NONE) null
                            else ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(preset.adjust.colorMatrix())),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        AdjustSlider(vm, R.string.ie_brightness, adj.brightness, -100..100) { v, a -> a.copy(brightness = v) }
        AdjustSlider(vm, R.string.ie_contrast, adj.contrast, -100..100) { v, a -> a.copy(contrast = v) }
        AdjustSlider(vm, R.string.ie_saturation, adj.saturation, -100..100) { v, a -> a.copy(saturation = v) }
        AdjustSlider(vm, R.string.ie_warmth, adj.warmth, -100..100) { v, a -> a.copy(warmth = v) }
        AdjustSlider(vm, R.string.ie_vignette, adj.vignette, 0..100) { v, a -> a.copy(vignette = v) }

        OutlinedButton(onClick = { vm.resetAdjust() }, enabled = !adj.isIdentity) {
            Text(stringResource(R.string.ie_adjust_reset))
        }
    }
}

/** منزلق تعديل: السحب يحدّث المعاينة بلا تسجيل، ورفع الإصبع يسجّل إدخال تاريخ واحداً. */
@Composable
private fun AdjustSlider(
    vm: ImageEditorViewModel, labelRes: Int, value: Int, range: IntRange,
    set: (Int, Adjust) -> Adjust
) {
    LabeledSlider(stringResource(labelRes), value.toFloat(), range.first.toFloat()..range.last.toFloat()) { v, done ->
        vm.beginSliderCommit()
        vm.updateAdjust { set(v.toInt(), it) }
        if (done) vm.endSliderCommit()
    }
}
