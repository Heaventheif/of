package org.openfilemanager.core

/**
 * تصنيف الملف بالامتداد — مصدر وحيد لجداول الأنواع في الوحدتين.
 * المجلدات مسؤولية المستدعي: هذا التصنيف للأسماء فقط.
 */
enum class FileKind {
    IMAGE, VIDEO, AUDIO, ARCHIVE, PDF,
    DOCUMENT, SPREADSHEET, PRESENTATION, CODE, TEXT, OTHER
}

object FileKinds {
    private val BY_EXTENSION: Map<String, FileKind> = buildMap {
        fun register(kind: FileKind, vararg extensions: String) {
            for (extension in extensions) put(extension, kind)
        }
        register(FileKind.IMAGE, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
        register(FileKind.VIDEO, "mp4", "mkv", "avi", "mov", "webm", "3gp")
        register(FileKind.AUDIO, "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus")
        register(FileKind.ARCHIVE, "zip", "rar", "7z", "tar", "gz", "xz", "jar", "apk", "cbz", "epub", "tgz", "txz")
        register(FileKind.PDF, "pdf")
        register(FileKind.DOCUMENT, "doc", "docx", "odt", "rtf")
        register(FileKind.SPREADSHEET, "xls", "xlsx", "csv", "ods")
        register(FileKind.PRESENTATION, "ppt", "pptx", "odp")
        register(FileKind.CODE, "kt", "java", "py", "js", "ts", "html", "css", "xml", "json", "c", "cpp", "h")
        register(FileKind.TEXT, "txt", "md", "log", "yml", "yaml", "ini", "conf", "properties", "sh")
    }

    /**
     * امتدادات تُفتح في المحرر النصي الداخلي (كانت TEXT_EXTENSIONS في MainActivity):
     * نص وكود وجداول CSV فقط — لا مستندات doc الغنية ولا ملفات py/ts/c… كما كان.
     */
    private val EDITABLE_TEXT_EXTENSIONS = setOf(
        "txt", "md", "json", "xml", "csv", "log", "kt", "java", "html", "css",
        "js", "yml", "yaml", "ini", "conf", "properties", "sh"
    )

    /** امتدادات لا يدعمها المستخرج فعلياً (rar/7z) فلا يُعرض لها أمر «استخراج». */
    private val UNEXTRACTABLE_EXTENSIONS = setOf("rar", "7z")

    fun of(name: String): FileKind =
        BY_EXTENSION[extensionOf(name)] ?: FileKind.OTHER

    fun isImage(name: String): Boolean = of(name) == FileKind.IMAGE

    fun isVideo(name: String): Boolean = of(name) == FileKind.VIDEO

    fun isArchive(name: String): Boolean = of(name) == FileKind.ARCHIVE

    /** أرشيف يظهر خيار استخراجه: أرشيف فعلاً وقابل للفك بمحرك الاستخراج الحالي. */
    fun isExtractableArchive(name: String): Boolean =
        isArchive(name) && extensionOf(name) !in UNEXTRACTABLE_EXTENSIONS

    fun isEditableText(name: String): Boolean = extensionOf(name) in EDITABLE_TEXT_EXTENSIONS

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()
}
