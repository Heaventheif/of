package org.openfilemanager.app

import android.webkit.MimeTypeMap
import org.openfilemanager.core.FileKinds
import org.openfilemanager.core.MimeTypes
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class SortField { NAME, SIZE, MODIFIED, TYPE }

enum class SortDirection { ASCENDING, DESCENDING }

data class FileProperties(
    val name: String,
    val type: String,
    val sizeBytes: Long,
    val readableSize: String,
    val modified: Long,
    /** تاريخ الإنشاء إن وفره نظام الملفات (غير مطابق لتاريخ التعديل). */
    val createdAt: Long?,
    /** أبعاد الصورة (عند الانطباق فقط). */
    val imageWidth: Int?,
    val imageHeight: Int?,
    /** مدة الوسائط بالميلي ثانية إن انطبقت. */
    val durationMs: Long?,
    val location: String,
    val canRead: Boolean,
    val canWrite: Boolean
)

/**
 * يقرأ الخصائص كاملة بما فيها الإضافات (تاريخ الإنشاء/الأبعاد/المدة).
 * عمليات إدخال/إخراج صريحة (readAttributes وفك حدود الصورة وMediaMetadataRetriever)
 * فلا تُستدعى على الخيط الرئيسي — المستدعي مسؤول عن ذلك.
 */
fun FileEntry.properties(): FileProperties {
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
    val coreMime = MimeTypes.byExtension(name)
    val createdAt: Long? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java).creationTime()?.toMillis()
    }.getOrNull()
    val isImage = MimeTypes.isImage(coreMime)
    // استخدام MediaProbe الموحّد بدل تكرار BitmapFactory.Options هنا.
    val dimensions: Pair<Int, Int>? = if (isImage && !isDirectory) MediaProbe.imageBounds(path) else null
    val isMedia = MimeTypes.isAudio(coreMime) || coreMime?.startsWith("video/") == true ||
        FileKinds.isVideo(name)
    val duration: Long? = if (isMedia && !isDirectory) MediaProbe.mediaDuration(path) else null
    return FileProperties(
        name = name,
        type = mime ?: if (isDirectory) "inode/directory" else "application/octet-stream",
        sizeBytes = size,
        readableSize = readableBytes(size),
        modified = modified,
        createdAt = createdAt?.takeIf { it > 0 && it != modified },
        imageWidth = dimensions?.first,
        imageHeight = dimensions?.second,
        durationMs = duration,
        location = path.toString(),
        canRead = Files.isReadable(path),
        canWrite = Files.isWritable(path)
    )
}

fun readableBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble(); var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    val format = DecimalFormat("0.##", DecimalFormatSymbols(Locale.ROOT))
    return "${format.format(value)} ${units[index]}"
}
