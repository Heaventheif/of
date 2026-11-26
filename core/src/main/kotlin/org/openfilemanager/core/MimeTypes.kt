package org.openfilemanager.core

/**
 * كشف نوع الملف: بصمة أول بايتات المحتوى أولاً، ثم الامتداد كخيار أخير.
 * البصمات: JPEG, PNG, GIF, PDF, ZIP/APK, GZ, XZ, TAR, MP3(ID3), OGG, FLAC, WAV, M4A/MP4.
 * تُقرأ أول 16 بايتاً فقط؛ الاستدعاء رخيص والملف الضخم لا يُحمَّل.
 */
object MimeTypes {
    private data class Magic(val mime: String, val bytes: ByteArray, val offset: Int = 0) {
        fun matches(data: ByteArray): Boolean {
            if (data.size < offset + bytes.size) return false
            for (i in bytes.indices) if (data[offset + i] != bytes[i]) return false
            return true
        }
    }

    private fun b(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val MAGICS = listOf(
        Magic("image/jpeg", b(0xFF, 0xD8, 0xFF)),
        Magic("image/png", b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        Magic("image/gif", b(0x47, 0x49, 0x46, 0x38)),
        Magic("application/pdf", b(0x25, 0x50, 0x44, 0x46)),
        Magic("application/zip", b(0x50, 0x4B, 0x03, 0x04)),
        Magic("application/zip", b(0x50, 0x4B, 0x05, 0x06)),
        Magic("application/zip", b(0x50, 0x4B, 0x07, 0x08)),
        Magic("application/gzip", b(0x1F, 0x8B)),
        Magic("application/x-xz", b(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)),
        Magic("application/x-tar", b(0x75, 0x73, 0x74, 0x61, 0x72), offset = 257),
        Magic("audio/mpeg", b(0x49, 0x44, 0x33)),
        Magic("audio/ogg", b(0x4F, 0x67, 0x67, 0x53)),
        Magic("audio/flac", b(0x66, 0x4C, 0x61, 0x43)),
        Magic("audio/wav", b(0x52, 0x49, 0x46, 0x46)),
        Magic("audio/mp4", b(0x66, 0x74, 0x79, 0x70, 0x4D, 0x34, 0x41, 0x20), offset = 4)
    )

    /** الامتدادات الشائعة حين تفشل البصمة أو الملف أصغر من أي بصمة. */
    private val BY_EXTENSION = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "svg" to "image/svg+xml",
        "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
        "xml" to "text/xml", "html" to "text/html", "css" to "text/css", "csv" to "text/csv",
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "zip" to "application/zip", "apk" to "application/vnd.android.package-archive",
        "jar" to "application/java-archive", "gz" to "application/gzip", "xz" to "application/x-xz",
        "tar" to "application/x-tar", "tgz" to "application/gzip", "txz" to "application/x-xz",
        "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav",
        "m4a" to "audio/mp4", "aac" to "audio/aac", "opus" to "audio/ogg", "mid" to "audio/midi"
    )

    /** يقرأ أول بايتات الملف ويعيد MIME من البصمة، أو null إن لم تطابق أي بصمة. */
    fun sniff(head: ByteArray): String? {
        if (head.isEmpty()) return null
        for (m in MAGICS) if (m.matches(head)) return m.mime
        if (head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) return "audio/mpeg"
        return null
    }

    /** الكشف الكامل: بصمة المحتوى أولاً ثم الامتداد (الامتداد آخر خيار). */
    fun detect(head: ByteArray, name: String): String? =
        sniff(head) ?: byExtension(name)

    fun byExtension(name: String): String? =
        name.substringAfterLast('.', "").lowercase().let { if (it.isEmpty()) null else BY_EXTENSION[it] }

    /** هل الملف قابل للعرض في عارض الصور الداخلي؟ */
    fun isImage(mime: String?): Boolean = mime != null && mime.startsWith("image/") && mime != "image/svg+xml"

    /** هل الملف صوت يشغله المشغل الداخلي؟ */
    fun isAudio(mime: String?): Boolean = mime != null && (mime.startsWith("audio/") || mime == "audio/mp4")

    /** هل الملف فيديو؟ (لا مشغل داخلي — يفتح خارجياً) */
    fun isVideo(mime: String?): Boolean = mime != null && mime.startsWith("video/")

}
