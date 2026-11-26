package org.openfilemanager.app

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.withContext

/** تصنيفات المكتبة + تصنيف الفيديو. التسميات في strings.xml. */
enum class LibraryTab { IMAGES, VIDEOS, AUDIO, DOCUMENTS, APK, NEW }

/** عنصر مكتبة: مرجع للملف لا نسخة منه. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    /** ثواني DATE_ADDED مضروبة في ألف. */
    val addedAt: Long,
    val modifiedAt: Long,
    val mimeType: String?,
    /** اسم المجلد الحاوي (BUCKET_DISPLAY_NAME) لصور المكتبة — لتجميعها حسب المجلد. */
    val bucket: String?,
    /** عمود DATA (مهجّر لكنه معبأ): لفتح الملف بمعرفته في العارض والمشغل والمثبّت. */
    val dataPath: String?
) {
    val idKey: String get() = "media:$id"
}

/** المكتبة: استعلامات MediaStore بصفحة واحدة في المرة (QUERY_ARG_LIMIT/OFFSET فلا تُحمّل كل الصفوف)، وتصنيف فيديو مخصص على جدول MediaStore.Video، مع. */
class MediaLibrary(private val context: Context) {
    companion object {
        const val PAGE_SIZE = 200
        const val APK_MIME = "application/vnd.android.package-archive"

        private val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )

        /** تبويب المستندات كان يعرض كل ملف غير وسائط (‎.so ‎.jks ‎.css ‎.srt…)، فتظهر فيه محتويات المشاريع والأرشيفات المفكوكة. */
        private const val MIME = MediaStore.Files.FileColumns.MIME_TYPE
        private const val DATA = MediaStore.MediaColumns.DATA
        private val DOC_EXTENSIONS = listOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
            "rtf", "epub", "txt", "md", "csv"
        )
        private val DOCUMENTS_SELECTION: String = buildString {
            append("(")
            append("$MIME = 'application/pdf' OR $MIME = 'application/msword' OR ")
            append("$MIME LIKE 'application/vnd.ms-excel%' OR $MIME LIKE 'application/vnd.ms-powerpoint%' OR ")
            append("$MIME LIKE 'application/vnd.ms-word%' OR ")
            append("$MIME LIKE 'application/vnd.openxmlformats-officedocument.%' OR ")
            append("$MIME LIKE 'application/vnd.oasis.opendocument.%' OR ")
            append("$MIME = 'application/rtf' OR $MIME = 'text/rtf' OR $MIME = 'application/epub+zip' OR ")
            append("$MIME = 'text/plain' OR $MIME = 'text/csv' OR $MIME = 'text/markdown' OR $MIME = 'text/x-markdown'")
            DOC_EXTENSIONS.forEach { append(" OR $DATA LIKE '%.$it'") }
            append(") AND $DATA NOT LIKE '%/Android/data/%' AND $DATA NOT LIKE '%/Android/obb/%'")
        }

        /** جدول الصور يُستعلم باسم مجلدها الحاوي (تجميع حسب المجلد). */
        private val PROJECTION_WITH_BUCKET = PROJECTION + arrayOf("bucket_display_name")
    }

    /**
     * صفحة واحدة من التصنيف؛ [offset] يبدأ من صفر. [groupImagesByFolder] (للصور فقط) يرتّب حسب المجلد الحاوي ثم التاريخ ويعيد اسم المجلد مع كل عنصر
     * لتعرض الواجهة رؤوس أقسام.
     */
    suspend fun page(tab: LibraryTab, offset: Int, groupImagesByFolder: Boolean = false): List<MediaItem> =
        withContext(AppDispatchers.io) {
            val resolver = context.contentResolver
            val uri = when (tab) {
                LibraryTab.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                LibraryTab.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                LibraryTab.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Files.getContentUri("external")
            }
            val selection: String? = when (tab) {
                LibraryTab.IMAGES -> null
                LibraryTab.VIDEOS -> null
                LibraryTab.AUDIO -> null
                LibraryTab.DOCUMENTS -> DOCUMENTS_SELECTION
                LibraryTab.APK -> "${MediaStore.Files.FileColumns.MIME_TYPE} = '$APK_MIME'"
                LibraryTab.NEW ->
                    "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
            }
            val sortOrder = when {
                tab == LibraryTab.NEW -> "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                tab == LibraryTab.IMAGES && groupImagesByFolder ->
                    "BUCKET_DISPLAY_NAME ASC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                else -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            }
            val projection = if (tab == LibraryTab.IMAGES) PROJECTION_WITH_BUCKET else PROJECTION
            val args = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }
            val items = ArrayList<MediaItem>(PAGE_SIZE)
            resolver.query(uri, projection, args, null)?.use { c -> read(c, uri, items, tab == LibraryTab.IMAGES) }
            items
        }

    private fun read(c: Cursor, baseUri: Uri, out: ArrayList<MediaItem>, withBucket: Boolean) {
        val colId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val colMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val colName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val colSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val colAdded = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val colModified = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val colData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        val colBucket = if (withBucket) c.getColumnIndexOrThrow("bucket_display_name") else -1
        while (c.moveToNext()) {
            val id = c.getLong(colId)
            out += MediaItem(
                id = id,
                uri = android.content.ContentUris.withAppendedId(baseUri, id),
                name = c.getString(colName) ?: "",
                size = c.getLong(colSize),
                addedAt = c.getLong(colAdded) * 1000,
                modifiedAt = c.getLong(colModified) * 1000,
                mimeType = c.getString(colMime),
                bucket = if (colBucket >= 0) c.getString(colBucket) else null,
                dataPath = c.getString(colData)
            )
        }
    }

    /** تحديث المسارات المتأثرة فقط بعد عمليات التطبيق. */
    fun scanFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    /**
     * مراقبة تغييرات المكتبة: تسجيل + إلغاء في مكانهما. يعيد دالة الإلغاء؛
     * المستدعي ملزم باستدعائها عند مغادرة الشاشة.
     */
    fun observeChanges(onChange: () -> Unit): () -> Unit {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(pending)
                handler.postDelayed(pending, 300)
            }
            private val pending = Runnable { onChange() }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        resolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"), false, observer
        )
        return {
            resolver.unregisterContentObserver(observer)
            handler.removeCallbacksAndMessages(null)
        }
    }
}
