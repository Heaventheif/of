package org.openfilemanager.app.ui

import org.openfilemanager.core.FolderSort
import org.openfilemanager.core.SortDirectionCore
import org.openfilemanager.core.SortFieldCore

/** منطق ترتيب القائمة المستخرج من BrowserViewModel.kt (N5) — لا تغيير في السلوك. */

internal fun sortedFileItems(items: List<FileItem>, sort: FolderSort, foldersFirst: Boolean): List<FileItem> {
    val base: Comparator<FileItem> = when (sort.field) {
        SortFieldCore.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
        SortFieldCore.SIZE -> compareBy { it.size }
        SortFieldCore.MODIFIED -> compareBy { it.modified }
        SortFieldCore.TYPE -> Comparator { a, b ->
            val ea = a.name.substringAfterLast('.', "").lowercase()
            val eb = b.name.substringAfterLast('.', "").lowercase()
            ea.compareTo(eb).takeIf { it != 0 } ?: naturalCompare(a.name, b.name)
        }
    }
    val ordered = if (sort.direction == SortDirectionCore.DESCENDING) base.reversed() else base
    return if (foldersFirst) items.sortedWith(compareByDescending<FileItem> { it.isDir }.then(ordered))
    else items.sortedWith(ordered)
}

/** "2.jpg" < "10.jpg": digit runs compare as numbers, everything else case-insensitively. */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
            var je = j; while (je < b.length && b[je].isDigit()) je++
            val na = a.substring(i, ie).trimStart('0'); val nb = b.substring(j, je).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ie; j = je
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}
