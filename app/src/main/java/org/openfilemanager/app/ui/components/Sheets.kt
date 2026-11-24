package org.openfilemanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.theme.Dim
import org.openfilemanager.core.FolderSort
import org.openfilemanager.core.SortDirectionCore
import org.openfilemanager.core.SortFieldCore

data class SheetAction(
    val icon: ImageVector, val label: String,
    val destructive: Boolean = false, val dividerBefore: Boolean = false,
    val onClick: () -> Unit
)

/** One consistent bottom sheet for every menu: item actions, selection actions, overflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            if (title != null) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Dim.Gutter + 8.dp, vertical = 8.dp)
                )
            }
            actions.forEach { a ->
                if (a.dividerBefore) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dim.SheetItem)
                        .clickable { onDismiss(); a.onClick() }
                        .padding(horizontal = Dim.Gutter + 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BorderedIcon(a.icon, tint = if (a.destructive) tint else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(18.dp))
                    Text(a.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(current: FolderSort, onPick: (FolderSort) -> Unit, onDismiss: () -> Unit) {
    val fields = listOf(
        SortFieldCore.NAME to R.string.ui_sort_name, SortFieldCore.MODIFIED to R.string.ui_sort_modified,
        SortFieldCore.SIZE to R.string.ui_sort_size, SortFieldCore.TYPE to R.string.ui_sort_type
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.ui_sort_by), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dim.Gutter + 8.dp, vertical = 8.dp)
            )
            fields.forEach { (field, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dim.SheetItem)
                        .clickable { onPick(current.copy(field = field)); onDismiss() }
                        .padding(horizontal = Dim.Gutter + 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (field == current.field) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = Dim.Gutter + 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = current.direction == SortDirectionCore.ASCENDING,
                    onClick = { onPick(current.copy(direction = SortDirectionCore.ASCENDING)); onDismiss() },
                    label = { Text(stringResource(R.string.ui_ascending)) }
                )
                FilterChip(
                    selected = current.direction == SortDirectionCore.DESCENDING,
                    onClick = { onPick(current.copy(direction = SortDirectionCore.DESCENDING)); onDismiss() },
                    label = { Text(stringResource(R.string.ui_descending)) }
                )
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}
