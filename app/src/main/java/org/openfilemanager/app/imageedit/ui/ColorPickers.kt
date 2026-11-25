package org.openfilemanager.app.imageedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openfilemanager.app.R

/** ألوان جاهزة متسقة مع هوية التطبيق (الأخضر الأساسي بينها). */
private val EDITOR_PALETTE = longArrayOf(
    0xFFFFFFFF, 0xFF000000, 0xFF00E476, 0xFFE53935, 0xFFFB8C00,
    0xFFFDD835, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60, 0xFF00ACC1
)

/**
 * صف ألوان محرّر الصور: عشرة لون جاهز + خانة «لون مخصص» تفتح منتقي تدرّج/تشبّع/
 * إضاءة inline تحتها. تُستعمل في النص والأشكال والرسم بدل صف الألوان القديم.
 */
@Composable
fun EditorColorRow(selected: Long, onPick: (Long) -> Unit) {
    val customLabel = stringResource(R.string.ie_custom_color)
    var custom by rememberSaveable { mutableStateOf(false) }
    var hue by rememberSaveable { mutableFloatStateOf(120f) }
    var sat by rememberSaveable { mutableFloatStateOf(80f) }
    var bri by rememberSaveable { mutableFloatStateOf(80f) }
    val customColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat / 100f, bri / 100f)))
    val inPalette = EDITOR_PALETTE.contains(selected)

    val customArgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat / 100f, bri / 100f)).toLong() and 0xFFFFFFFFL
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EDITOR_PALETTE.forEach { c ->
                ColorSwatch(Color(c.toInt()), c == selected) { onPick(c) }
            }
            ColorSwatch(customColor, custom && !inPalette, customLabel) {
                if (!custom && !inPalette) {
                    // فتح المنتقي: يبدأ من اللون المخصص الحالي إن وُجد
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(selected.toInt(), hsv)
                    hue = hsv[0]; sat = hsv[1] * 100f; bri = hsv[2] * 100f
                }
                custom = !custom
                if (custom) onPick(customArgb)
            }
        }
        if (custom) {
            ColorPartSlider(R.string.ie_color_hue, hue, 0f..360f) { hue = it }
            ColorPartSlider(R.string.ie_color_saturation, sat, 0f..100f) { sat = it }
            ColorPartSlider(R.string.ie_color_brightness, bri, 0f..100f) { bri = it }
            androidx.compose.runtime.LaunchedEffect(customArgb) { onPick(customArgb) }
        }
    }
}

@Composable
private fun ColorPartSlider(labelRes: Int, value: Float, range: ClosedFloatingPointRange<Float>, on: (Float) -> Unit) {
    LabeledSlider(stringResource(labelRes), value, range) { v, _ -> on(v) }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val m = if (description != null) {
        Modifier.semantics { contentDescription = description }
    } else Modifier
    Box(
        m
            .size(30.dp)
            .background(color, CircleShape)
            .border(androidx.compose.foundation.BorderStroke(if (selected) 3.dp else 1.dp, border), CircleShape)
            .clickable(onClick = onClick)
    )
}
