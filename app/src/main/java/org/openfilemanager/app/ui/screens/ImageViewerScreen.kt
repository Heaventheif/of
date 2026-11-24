package org.openfilemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import android.graphics.BitmapFactory
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.FileOperationService
import org.openfilemanager.app.OpRequest
import org.openfilemanager.app.ui.components.ActionSheet
import org.openfilemanager.app.ui.components.SheetAction
import org.openfilemanager.app.ui.openWithSystem
import org.openfilemanager.app.imageedit.model.isEditableImage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import java.io.File
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.shareFiles

/** Immersive viewer: swipe between photos, pinch/double-tap zoom, tap toggles an always-available back bar. */
@Composable
fun ImageViewerScreen(
    paths: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
    onEdit: ((path: String, page: Int) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0))) { paths.size }
    var chrome by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val current = paths[pager.currentPage]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { paths[it] }) { page ->
            ZoomableImage(paths[page], onTap = { chrome = !chrome })
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopStart)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back), tint = Color.White) }
                Text(
                    BidiText.wrap(File(current).name), color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                // LRI..PDI keeps "7 / 37" in reading order inside a right-to-left screen.
                Text("\u2066${pager.currentPage + 1} / ${paths.size}\u2069", color = Color.White.copy(alpha = 0.8f))
                IconButton({ shareFiles(ctx, listOf(current)) }) {
                    Icon(Icons.Outlined.Share, stringResource(R.string.ui_share), tint = Color.White)
                }
                IconButton({ showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.ui_more), tint = Color.White)
                }
            }
        }
        if (showMenu) ActionSheet(
            BidiText.wrap(File(current).name),
            buildList {
                add(SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open_with)) { openWithSystem(ctx, current) })
                add(SheetAction(Icons.Outlined.Info, stringResource(R.string.ui_details)) { showDetails = true })
                // التعديل من قائمة الخيارات فقط — لا أيقونة مستقلة في العارض
                if (onEdit != null && isEditableImage(current)) {
                    add(
                        SheetAction(Icons.Outlined.AutoFixHigh, stringResource(R.string.ie_edit_image)) {
                            onEdit(current, pager.currentPage)
                        }
                    )
                }
                add(SheetAction(Icons.Outlined.Delete, stringResource(R.string.ui_delete), destructive = true, dividerBefore = true) {
                    FileOperationService.enqueue(ctx, OpRequest.Delete(listOf(File(current).toPath())))
                    onBack()
                })
            }
        ) { showMenu = false }
        if (showDetails) {
            val f = File(current)
            val dims = remember(current) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(current, o)
                if (o.outWidth > 0) "\u2066${o.outWidth} × ${o.outHeight}\u2069" else "—"
            }
            AlertDialog(
                onDismissRequest = { showDetails = false },
                title = { Text(BidiText.wrap(f.name)) },
                text = {
                    Column {
                        Text(stringResource(R.string.ui_detail_size) + ": " + Formatter.formatFileSize(ctx, f.length()))
                        Text(stringResource(R.string.ui_detail_dims) + ": " + dims)
                        Text(stringResource(R.string.ui_detail_date) + ": " + DateFormat.getDateTimeInstance().format(Date(f.lastModified())))
                        Text(stringResource(R.string.ui_detail_path) + ": " + BidiText.wrap(f.parent ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton({ showDetails = false }) { Text(stringResource(R.string.ui_ok)) } }
            )
        }
    }
}

@Composable
private fun ZoomableImage(path: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val tState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        val maxX = box.width * (scale - 1f) / 2f
        val maxY = box.height * (scale - 1f) / 2f
        offset = if (scale <= 1f) Offset.Zero else Offset(
            (offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY)
        )
    }
    AsyncImage(
        model = File(path), contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f }
                )
            }
            // canPan = false at 1x so a one-finger swipe still reaches the pager.
            .transformable(tState, canPan = { scale > 1f })
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
    )
}
