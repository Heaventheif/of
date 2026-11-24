package org.openfilemanager.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** نوع الرسالة: نجاح (أخضر) · فشل (أحمر) · معلومة محايدة. */
enum class SnackKind { SUCCESS, ERROR, INFO }

/** محتوى Snackbar يحمل نوعه ليلوّنه المضيف. المدة يديرها [show] لا المكتبة (قصيرة عمداً). */
class OfmSnackbarVisuals(
    override val message: String,
    val kind: SnackKind,
    override val actionLabel: String? = null
) : SnackbarVisuals {
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
    override val withDismissAction: Boolean get() = false
}

/** مدد العرض بالمللي ثانية: النجاح أقصرها، والفشل أطول قليلاً ليُقرأ، ومع زر الإجراء (تراجع) أطول. */
private fun displayMillis(kind: SnackKind, hasAction: Boolean): Long = when {
    hasAction -> 4_500L
    kind == SnackKind.ERROR -> 2_800L
    kind == SnackKind.INFO -> 1_800L
    else -> 1_600L
}

/**
 * عرض رسالة بنوع محدد: أخضر للنجاح وأحمر للفشل، وتختفي بسرعة.
 * المؤقت يبدأ حين تصبح هذه الرسالة هي الظاهرة فعلاً (لا أثناء انتظارها في الطابور).
 */
suspend fun SnackbarHostState.show(
    message: String,
    kind: SnackKind,
    actionLabel: String? = null
): SnackbarResult = coroutineScope {
    val visuals = OfmSnackbarVisuals(message, kind, actionLabel)
    val timer = launch {
        snapshotFlow { currentSnackbarData }.first { it?.visuals === visuals }
        delay(displayMillis(kind, actionLabel != null))
        currentSnackbarData?.takeIf { it.visuals === visuals }?.dismiss()
    }
    try {
        showSnackbar(visuals)
    } finally {
        timer.cancel()
    }
}

/** حركة الدخول: ارتفاع نابض خفيف + تلاشٍ + تكبير بسيط. الخروج: تلاشٍ سريع (≈110ms) مع انزلاق صغير. */
private const val EXIT_MILLIS = 110

private val SnackEnter = fadeIn(tween(160, delayMillis = 40)) +
    slideInVertically(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } +
    scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium), initialScale = 0.92f)

private val SnackExit = fadeOut(tween(EXIT_MILLIS, easing = FastOutLinearInEasing)) +
    slideOutVertically(tween(EXIT_MILLIS, easing = FastOutLinearInEasing)) { it / 4 } +
    scaleOut(tween(EXIT_MILLIS), targetScale = 0.96f)

/**
 * مضيف رسائل احترافي: بطاقة Material 3 بألوان الدور (نجاح/خطأ/معلومة) مع أيقونة،
 * دخول نابض وخروج سريع بالتلاشي، وسحب أفقي للإغلاق. يقرأ المحتوى من [SnackbarHostState]
 * مباشرة بدل SnackbarHost الجاهز كي نتحكم بالحركة؛ المدة يديرها [show] كما كانت.
 */
@Composable
fun OfmSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val data = state.currentSnackbarData
    AnimatedContent(
        targetState = data,
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            // لا حركة حجم: الرسالة الجديدة تحل محل القديمة مباشرة (القديمة تتلاشى بسرعة).
            (SnackEnter togetherWith SnackExit).using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "snackbar"
    ) { d ->
        if (d != null) SnackbarCard(d)
    }
}

@Composable
private fun SnackbarCard(data: SnackbarData) {
    val kind = (data.visuals as? OfmSnackbarVisuals)?.kind ?: SnackKind.INFO
    val scheme = MaterialTheme.colorScheme
    val container = when (kind) {
        SnackKind.SUCCESS -> scheme.primaryContainer
        SnackKind.ERROR -> scheme.errorContainer
        SnackKind.INFO -> scheme.inverseSurface
    }
    val content = when (kind) {
        SnackKind.SUCCESS -> scheme.onPrimaryContainer
        SnackKind.ERROR -> scheme.onErrorContainer
        SnackKind.INFO -> scheme.inverseOnSurface
    }
    val icon = when (kind) {
        SnackKind.SUCCESS -> Icons.Outlined.CheckCircle
        SnackKind.ERROR -> Icons.Outlined.ErrorOutline
        SnackKind.INFO -> Icons.Outlined.Info
    }
    val scope = rememberCoroutineScope()
    val offset = remember(data) { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 72.dp.toPx() }

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = content,
            shadowElevation = 6.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(offset.value) / (dismissPx * 2.5f)).coerceIn(0f, 0.85f) }
                .pointerInput(data) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(offset.value) > dismissPx) {
                                    // اترك تلاشي الخروج يكمل من موضع السحب الحالي.
                                    data.dismiss()
                                } else offset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } }
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + dx) }
                    }
                }
        ) {
            Row(
                Modifier.padding(
                    start = 14.dp, end = if (data.visuals.actionLabel != null) 6.dp else 16.dp,
                    top = 10.dp, bottom = 10.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, null, Modifier.size(22.dp))
                Text(
                    data.visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton(
                        { data.performAction() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (kind == SnackKind.INFO) scheme.inversePrimary else content
                        )
                    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}
