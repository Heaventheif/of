package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * كشف صيغة الأرشيف: بصمة المحتوى أولاً ثم الامتداد، مع مساعدات
 * تسمية وجهة الاستخراج. مشترك بين [ArchiveExtractor] و[ArchiveInspector]
 * وطبقة التطبيق (ArchiveService) — عام عمداً لأن التطبيق يحتاج أسماء
 * وجهات الاستخراج بنفس القواعد.
 */
object ArchiveFormat {
    enum class Kind { ZIP, TAR, TAR_GZ, TAR_XZ, GZ, XZ }

    /** الكشف ببصمة الملف أولاً (zip/gz/xz)، ثم اسمه لمعرفة أن المحتوى TAR. */
    fun detect(archive: Path): Kind {
        val magic = ByteArray(6)
        val read = Files.newInputStream(archive).use { it.readUpTo(magic, 0, magic.size) }
        val name = archive.fileName.toString().lowercase()
        val tarName = name.endsWith(".tar") || name.contains(".tar.") || name.endsWith(".tgz") || name.endsWith(".txz")
        fun b(i: Int, v: Int) = magic[i] == v.toByte()
        return when {
            read >= 4 && b(0, 0x50) && b(1, 0x4B) -> Kind.ZIP
            read >= 2 && b(0, 0x1F) && b(1, 0x8B) -> if (tarName) Kind.TAR_GZ else Kind.GZ
            read >= 6 && b(0, 0xFD) && b(1, 0x37) && b(2, 0x7A) && b(3, 0x58) && b(4, 0x5A) && b(5, 0x00) ->
                if (tarName) Kind.TAR_XZ else Kind.XZ
            name.endsWith(".tar") -> Kind.TAR
            else -> throw IOException("unsupported archive format: $name")
        }
    }

    /** اسم مجلد الاستخراج من اسم الأرشيف (a.tar.gz ← a، b.zip ← b). */
    fun folderName(archiveName: String): String {
        val lower = archiveName.lowercase()
        val cut = listOf(".tar.gz", ".tar.xz", ".tgz", ".txz").firstOrNull { lower.endsWith(it) }
        val stripped = when {
            cut != null -> archiveName.dropLast(cut.length)
            archiveName.contains('.') -> archiveName.substringBeforeLast('.')
            else -> ""
        }
        return stripped.ifBlank { "$archiveName-extracted" }
    }

    /** اسم ملف الاستخراج للأرشيف المنفرد (notes.txt.gz ← notes.txt). */
    fun singleFileName(archiveName: String): String =
        archiveName.substringBeforeLast('.', "").ifBlank { "$archiveName-extracted" }
}
