package org.openfilemanager.app.ui

import java.io.File
import org.openfilemanager.core.FileKind
import org.openfilemanager.core.FolderSort

/** Where the browser currently is: a real folder, or a folder-like view inside an archive. */
sealed interface Location {
    data class Dir(val dir: File) : Location
    /** [inner] is "" at the archive root, else a prefix such as "src/main/". */
    data class Archive(val file: File, val inner: String) : Location
}

data class FileItem(
    val id: String,
    val name: String,
    val isDir: Boolean,
    /** -1 when unknown (folders). */
    val size: Long,
    val modified: Long,
    val kind: FileKind?,
    /** Absolute path for on-disk items; null for archive entries. */
    val path: String?,
    /** For archive items: the entry name / folder prefix inside the archive. */
    val entry: String? = null,
    /** Folder name shown for recursive search results. */
    val parent: String? = null
) {
    val isArchiveEntry: Boolean get() = path == null
}

/** Everything the bulk-rename dialog needs, in the order the numbering will follow. */
class BulkRenameContext(val inputs: List<org.openfilemanager.core.RenameInput>, val existing: List<String>, val strictFat: Boolean)

/** [archive] set = [paths] are entry names inside that archive file; null = paths on disk. */
data class Clipboard(val paths: List<String>, val move: Boolean, val archive: String? = null)

data class BrowserUiState(
    val location: Location,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = true,
    val selection: Set<String> = emptySet(),
    val sort: FolderSort = FolderSort(),
    val grid: Boolean = false,
    val showHidden: Boolean = false,
    val query: String? = null,
    val clipboard: Clipboard? = null,
    val archiveTruncated: Boolean = false,
    /** Recursive search results for the current folder (null = not searching). */
    val searchHits: List<FileItem>? = null,
    val searchBusy: Boolean = false,
    val searchTruncated: Boolean = false,
    /** مجلدات/عناصر تعذر الوصول إليها أثناء آخر بحث منجز — تميّز النتائج الجزئية عن الكاملة. */
    val searchErrors: Int = 0,
    /** The open archive is a ZIP that can be edited in place (saved only when the user confirms). */
    val archiveEditable: Boolean = false,
    val archiveDirty: Boolean = false,
    val saving: Boolean = false,
    /** Leaving an edited archive: ask Save / Don't save / Cancel. */
    val askSave: Boolean = false
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    val visibleItems: List<FileItem>
        get() {
            val q = query?.trim().orEmpty()
            return when {
                q.isEmpty() -> items
                searchHits != null -> searchHits
                else -> items.filter { it.name.contains(q, ignoreCase = true) }
            }
        }
}

sealed interface UiEvent {
    data class Message(val text: String, val kind: org.openfilemanager.app.ui.components.SnackKind = org.openfilemanager.app.ui.components.SnackKind.SUCCESS) : UiEvent
    /** Bulk rename finished on disk: snackbar with an Undo action. */
    data class RenameDone(val text: String) : UiEvent
    /** A single archive entry was extracted to cache and is ready to open. */
    data class OpenExtracted(val path: String, val entry: String? = null) : UiEvent
}

fun File.toFileItem(): FileItem {
    val dir = isDirectory
    return FileItem(
        id = absolutePath, name = name, isDir = dir, size = if (dir) -1L else length(), modified = lastModified(),
        kind = if (dir) null else org.openfilemanager.core.FileKinds.of(name), path = absolutePath
    )
}
