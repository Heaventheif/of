package org.openfilemanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.openfilemanager.app.ui.theme.BrandGreen
import org.openfilemanager.core.FileKind

/** Icon + accent for each file type. Real vector icons (Material Symbols), not text glyphs. */
data class KindStyle(val icon: ImageVector, val accent: Color)

/** Pastel accent for dark surfaces, deeper accent of the same hue for light surfaces (contrast >= 3:1). */
private fun tone(dark: Long, light: Long, isDark: Boolean) = Color(if (isDark) dark else light)

fun kindStyle(name: String, kind: FileKind?, isDir: Boolean, dark: Boolean = true): KindStyle = when {
    isDir -> KindStyle(Icons.Outlined.Folder, if (dark) BrandGreen else Color(0xFF006D3C))
    name.endsWith(".apk", ignoreCase = true) -> KindStyle(Icons.Outlined.Android, tone(0xFF9CCB7E, 0xFF3F6B21, dark))
    else -> when (kind) {
        FileKind.IMAGE -> KindStyle(Icons.Outlined.Image, tone(0xFF86B7D8, 0xFF1F5F8B, dark))
        FileKind.VIDEO -> KindStyle(Icons.Outlined.VideoFile, tone(0xFFD89B86, 0xFF8E4A33, dark))
        FileKind.AUDIO -> KindStyle(Icons.Outlined.AudioFile, tone(0xFFC49BD8, 0xFF7A3F94, dark))
        FileKind.ARCHIVE -> KindStyle(Icons.Outlined.FolderZip, tone(0xFFD8C486, 0xFF7A6215, dark))
        FileKind.PDF -> KindStyle(Icons.Outlined.PictureAsPdf, tone(0xFFD88686, 0xFFA33A3A, dark))
        FileKind.DOCUMENT -> KindStyle(Icons.Outlined.Description, tone(0xFF86A8D8, 0xFF2D5493, dark))
        FileKind.SPREADSHEET -> KindStyle(Icons.Outlined.TableChart, tone(0xFF86D8A0, 0xFF1B6B3A, dark))
        FileKind.PRESENTATION -> KindStyle(Icons.Outlined.Slideshow, tone(0xFFD8A886, 0xFF8E5426, dark))
        FileKind.CODE -> KindStyle(Icons.Outlined.Code, tone(0xFFA0D886, 0xFF467021, dark))
        FileKind.TEXT -> KindStyle(Icons.Outlined.TextSnippet, tone(0xFFB8BFB9, 0xFF5A615B, dark))
        else -> KindStyle(Icons.Outlined.InsertDriveFile, tone(0xFFA7AEA8, 0xFF5F665F, dark))
    }
}
