package org.openfilemanager.app.ui.screens

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.basicMarquee
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import org.openfilemanager.app.LyricLine
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.MusicPlaybackService
import org.openfilemanager.app.PlayerBus
import org.openfilemanager.app.R
import org.openfilemanager.app.RepeatMode
import org.openfilemanager.app.ui.theme.Dim

private fun clock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return "\u2066%d:%02d\u2069".format(t / 60, t % 60)
}

/** Real player screen: cover, title/artist, seek bar and controls. State comes from PlayerBus, commands go to the service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by PlayerBus.state.collectAsStateWithLifecycle()
    val cover by PlayerBus.cover.collectAsStateWithLifecycle()
    val lyrics by PlayerBus.lyrics.collectAsStateWithLifecycle()
    var drag by remember { mutableStateOf<Float?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_now_playing)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) } }
            )
        }
    ) { pad ->
        val s = st
        if (s == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ui_nothing_playing), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val bmp = cover
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(BidiText.wrap(s.title), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (s.artist.isNotBlank()) Text(
                BidiText.wrap(s.artist), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // المساحة المتبقية: معلومات المقطع أو الكلمات (سطر متحرك إن توفرت)
            InfoOrLyrics(s, lyrics, Modifier.weight(1f).fillMaxWidth())
            val dur = s.durationMs.coerceAtLeast(1L).toFloat()
            SeekBar(
                progress = drag ?: (s.positionMs / dur).coerceIn(0f, 1f),
                onChange = { drag = it },
                onFinish = {
                    drag?.let { MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_SEEK, MusicPlaybackService.EXTRA_POSITION_MS, (it * dur).toLong()) }
                    drag = null
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock((drag?.times(dur))?.toLong() ?: s.positionMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(clock(s.durationMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_TOGGLE_SHUFFLE) }) {
                    Icon(Icons.Outlined.Shuffle, stringResource(R.string.ui_shuffle),
                        tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_PREV) }, Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.SkipPrevious, stringResource(R.string.ui_previous), modifier = Modifier.size(36.dp))
                }
                FilledIconButton({ MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_TOGGLE) }, Modifier.size(72.dp)) {
                    Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.ui_play_pause), modifier = Modifier.size(40.dp))
                }
                IconButton({ MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_NEXT) }, Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.SkipNext, stringResource(R.string.ui_next), modifier = Modifier.size(36.dp))
                }
                IconButton({ MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_CYCLE_REPEAT) }) {
                    Icon(
                        if (s.repeat == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, stringResource(R.string.ui_repeat),
                        tint = if (s.repeat == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "\u2066${s.index + 1} / ${s.count}\u2069", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * منطقة بين العنوان وشريط التقدم: كلمات الأغنية كسطر واحد متحرك يتبدل مع الزمن إن وُجد ملف .lrc،
 * وإلا بطاقة معلومات (فنان/ألبوم/ترتيب) بسطر نصي متحرك للنص الطويل.
 */
@Composable
private fun InfoOrLyrics(s: org.openfilemanager.app.PlaybackState, lyrics: List<LyricLine>?, modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        val synced = lyrics?.takeIf { l -> l.isNotEmpty() && l.first().timeMs >= 0 }
        if (synced != null) {
            val cur = remember(synced, s.positionMs) {
                synced.indexOfLast { it.timeMs <= s.positionMs }
            }
            val line = synced.getOrNull(cur)?.text.orEmpty()
            AnimatedContent(
                targetState = line,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "lyric"
            ) { t ->
                Text(
                    BidiText.wrap(t.ifBlank { "♪" }),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        } else if (!lyrics.isNullOrEmpty()) {
            // نص غير متزامن: سطر واحد يتحرك أفقياً عبر كل الأسطر
            Text(
                BidiText.wrap(lyrics.joinToString("   •   ") { it.text }),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1, softWrap = false,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp)
            )
        } else {
            val parts = listOf(s.artist, s.album).filter { it.isNotBlank() }
            if (parts.isNotEmpty()) Text(
                BidiText.wrap(parts.joinToString("  •  ")),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, softWrap = false,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 30.dp)
            )
        }
    }
}

/** Thin track with a small round thumb (replaces the stock Slider's tall pill handle). Honors RTL. */
@Composable
private fun SeekBar(progress: Float, onChange: (Float) -> Unit, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.primary
    var dragging by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val pad = 8.dp
    Canvas(
        modifier.fillMaxWidth().height(28.dp)
            .pointerInput(rtl) {
                val p = pad.toPx()
                fun frac(x: Float): Float {
                    val f = ((x - p) / (size.width - 2 * p).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return if (rtl) 1f - f else f
                }
                detectTapGestures { o -> change(frac(o.x)); finish() }
            }
            .pointerInput(rtl) {
                val p = pad.toPx()
                fun frac(x: Float): Float {
                    val f = ((x - p) / (size.width - 2 * p).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return if (rtl) 1f - f else f
                }
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; change(frac(o.x)) },
                    onDragEnd = { dragging = false; finish() },
                    onDragCancel = { dragging = false; finish() },
                    onHorizontalDrag = { c, _ -> c.consume(); change(frac(c.position.x)) }
                )
            }
    ) {
        val p = pad.toPx()
        val w = size.width
        val cy = size.height / 2
        val f = progress.coerceIn(0f, 1f)
        fun px(v: Float): Float = if (rtl) w - (p + v * (w - 2 * p)) else p + v * (w - 2 * p)
        val stroke = 3.dp.toPx()
        drawLine(trackColor, Offset(p, cy), Offset(w - p, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(activeColor, Offset(px(0f), cy), Offset(px(f), cy), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(activeColor, radius = (if (dragging) 8.dp else 6.dp).toPx(), center = Offset(px(f), cy))
    }
}

/** Thin bar above the bottom navigation while something is playing. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val s by PlayerBus.state.collectAsStateWithLifecycle()
    val cover by PlayerBus.cover.collectAsStateWithLifecycle()
    // لا يظهر إلا لخدمة تعمل فعلاً ولديها قائمة تشغيل (حالة فارغة/قديمة = مخفي).
    // (تصحيح) مزامنة مع الخدمة عند كل عودة للتطبيق: إن كانت الخدمة حيّة نطلب منها نشر
    // حالتها الحالية (قد تغيرت أثناء الغياب بمقاطعة من تطبيق آخر)، وإن ماتت نمسح الحالة
    // القديمة كي لا يبقى مشغل بأزرار ميتة.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (MusicPlaybackService.running) MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_SYNC)
        else {
            PlayerBus.state.value = null
            PlayerBus.cover.value = null
            PlayerBus.lyrics.value = null
        }
    }
    val st = s?.takeIf { it.count > 0 && MusicPlaybackService.running } ?: return
    // navigationBarsPadding: المشغل فوق أزرار تنقل أندرويد لا خلفها.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.navigationBarsPadding().fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(horizontal = Dim.Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                val bmp = cover
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Text(BidiText.wrap(st.title), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton({ MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_TOGGLE) }) {
                Icon(if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.ui_play_pause))
            }
            IconButton({ MusicPlaybackService.command(ctx, MusicPlaybackService.ACTION_STOP) }) {
                Icon(Icons.Outlined.Stop, stringResource(R.string.ui_stop), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
