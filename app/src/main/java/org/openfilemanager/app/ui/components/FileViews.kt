package org.openfilemanager.app.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import java.io.File
import java.text.DateFormat
import java.util.Date
import org.openfilemanager.app.BidiText
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.FileItem
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.FileKind

internal sealed interface ThumbModel {
    data class DiskFile(val file: File) : ThumbModel
    data class AudioArtwork(val path: String, val modified: Long) : ThumbModel
    data class ApkIconSource(val path: String, val modified: Long) : ThumbModel
}

/**
 * Icon tile. Images/videos show their frame, audio shows its embedded cover, APKs show their real icon;
 * the type icon underneath is the loading/fallback state. Selection is a small badge, never a cover.
 */
@Composable
fun FileThumb(item: FileItem, selected: Boolean, modifier: Modifier = Modifier, size: Dp = Dim.Tile) {
    val style = kindStyle(item.name, item.kind, item.isDir, org.openfilemanager.app.ui.theme.isOfmDark())
    val ctx = LocalContext.current
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.small).background(style.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(style.icon, contentDescription = null, tint = style.accent, modifier = Modifier.size(size * 0.55f))
        val path = item.path
        val model: ThumbModel? = when {
            path == null -> null
            item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO -> ThumbModel.DiskFile(File(path))
            item.kind == FileKind.AUDIO -> ThumbModel.AudioArtwork(path, item.modified)
            item.name.endsWith(".apk", ignoreCase = true) -> ThumbModel.ApkIconSource(path, item.modified)
            else -> null
        }
        if (model != null) {
            AsyncImage(
                // ImageRequest.data يقبل Any (نقطة توسعة Coil)، لكن قيمتها هنا
                // محصورة بأنواع ThumbModel الثلاثة بلا أي كائن عابر.
                model = ImageRequest.Builder(ctx).data(when (model) {
                    is ThumbModel.DiskFile -> model.file
                    is ThumbModel.AudioArtwork -> AudioArt(model.path, model.modified)
                    is ThumbModel.ApkIconSource -> ApkIcon(model.path, model.modified)
                }).size(Size(192, 192)).build(),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(2.dp).size(16.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** Size, type and date as separate pieces: each is laid out as its own paragraph, so mixed Arabic/Latin never scrambles. */
@Composable
fun rememberMetaParts(item: FileItem): List<String> {
    val ctx = LocalContext.current
    val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    return remember(item.id, item.size, item.modified, item.parent) {
        buildList {
            if (item.isDir) add(ctx.getString(R.string.ui_folder))
            else {
                if (item.size >= 0) add(Formatter.formatShortFileSize(ctx, item.size))
                item.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
                    ?.let { add(it.uppercase()) }
            }
            if (item.modified > 0) add(fmt.format(Date(item.modified)))
            item.parent?.let { add(BidiText.wrap(it)) }
        }
    }
}

@Composable
fun MetaLine(item: FileItem) {
    val parts = rememberMetaParts(item)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            Text(
                p, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem, selected: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onMore: () -> Unit,
    modifier: Modifier = Modifier, showMore: Boolean = true
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().heightIn(min = Dim.RowMinHeight).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
            .padding(start = Dim.Gutter, end = if (selecting) Dim.Gutter else 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileThumb(item, selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(BidiText.wrap(item.name), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(item)
        }
        if (!selecting && showMore) {
            IconButton(onClick = onMore, modifier = Modifier.size(Dim.TouchTarget)) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.ui_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    item: FileItem, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val haptic = LocalHapticFeedback.current
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FileThumb(item, selected, size = 60.dp)
        Text(
            BidiText.wrap(item.name), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
}
