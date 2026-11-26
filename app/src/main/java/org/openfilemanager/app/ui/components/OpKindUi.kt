package org.openfilemanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.ui.graphics.vector.ImageVector
import org.openfilemanager.app.R
import org.openfilemanager.core.OpKind

fun kindLabelRes(kind: OpKind?): Int = when (kind) {
    OpKind.COPY -> R.string.op_kind_copy
    OpKind.MOVE -> R.string.op_kind_move
    OpKind.DELETE -> R.string.op_kind_delete
    OpKind.RENAME -> R.string.op_kind_rename
    OpKind.COMPRESS -> R.string.op_kind_compress
    OpKind.EXTRACT -> R.string.op_kind_extract
    null -> R.string.ui_op_history_kind_unknown
}

fun kindIcon(kind: OpKind?): ImageVector = when (kind) {
    OpKind.COPY -> Icons.Outlined.ContentCopy
    OpKind.MOVE -> Icons.Outlined.DriveFileMove
    OpKind.DELETE -> Icons.Outlined.Delete
    OpKind.RENAME -> Icons.Outlined.DriveFileRenameOutline
    OpKind.COMPRESS -> Icons.Outlined.FolderZip
    OpKind.EXTRACT -> Icons.Outlined.Unarchive
    null -> Icons.Outlined.History
}
