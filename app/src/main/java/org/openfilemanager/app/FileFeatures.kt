package org.openfilemanager.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.openfilemanager.core.FileKinds
import org.openfilemanager.core.MimeTypes
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Pure models for the report-defined search, sorting and filtering features. Batch rename lives in :core. */
data class SearchSpec(
    val query: String = "",
    val extension: String? = null,
    val foldersOnly: Boolean = false,
    val filesOnly: Boolean = false,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null
)

enum class SortField { NAME, SIZE, MODIFIED, TYPE }

enum class SortDirection { ASCENDING, DESCENDING }

data class SortSpec(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val foldersFirst: Boolean = true
)

fun List<FileEntry>.filterAndSort(search: SearchSpec, sort: SortSpec): List<FileEntry> {
    val q = search.query.trim().lowercase(Locale.ROOT)
    val filtered = asSequence()
        .filter { entry ->
            val name = entry.name.lowercase(Locale.ROOT)
            val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            (q.isEmpty() || name.contains(q)) &&
                (!search.foldersOnly || entry.isDirectory) &&
                (!search.filesOnly || !entry.isDirectory) &&
                (search.extension == null || extension == search.extension.trimStart('.').lowercase(Locale.ROOT)) &&
                (search.minSize == null || entry.size >= search.minSize) &&
                (search.maxSize == null || entry.size <= search.maxSize) &&
                (search.modifiedAfter == null || entry.modified >= search.modifiedAfter) &&
                (search.modifiedBefore == null || entry.modified <= search.modifiedBefore)
        }
        .toList()
    return if (sort.field == SortField.NAME) sortByName(filtered, sort) else filtered.sortedWith(entryComparator(sort))
}

/**
 * الفرز الطبيعي للأسماء: ICU `RuleBasedCollator` مع `setNumericCollation(true)`
 * فترتب `file2` قبل `file10` ويعمل مع الأرقام العربية الهندية، وغير حساس لحالة الأحرف
 * (SECONDARY)، ومفتاح ICU يُحسب مرة واحدة لكل عنصر.
 * RuleBasedCollator ليس آمناً للخيوط والفرز يجري على compute: كل خيط يبني نسخته
 * الخاصة.
 */
private val naturalCollator: ThreadLocal<android.icu.text.RuleBasedCollator?> =
    ThreadLocal.withInitial {
        runCatching {
            (android.icu.text.Collator.getInstance() as android.icu.text.RuleBasedCollator).apply {
                setStrength(android.icu.text.Collator.SECONDARY)
                setNumericCollation(true)
            }
        }.getOrNull()
    }

private fun sortByName(items: List<FileEntry>, sort: SortSpec): List<FileEntry> {
    val collator = naturalCollator.get()
    if (collator == null) return items.sortedWith(entryComparator(sort))
    val decorated = items.map { it to collator.getCollationKey(it.name) }
    val foldersFirst = compareByDescending<Pair<FileEntry, android.icu.text.CollationKey>> {
        if (sort.foldersFirst) it.first.isDirectory else false
    }
    val ordered = decorated.sortedWith(
        if (sort.direction == SortDirection.ASCENDING) foldersFirst.thenBy { it.second }
        else foldersFirst.thenByDescending { it.second }
    )
    return ordered.map { it.first }
}

private fun entryComparator(sort: SortSpec): Comparator<FileEntry> {
    val folderComparator = compareByDescending<FileEntry> { if (sort.foldersFirst) it.isDirectory else false }
    val valueComparator: Comparator<FileEntry> = when (sort.field) {
        SortField.NAME -> Comparator { left, right -> compareNatural(left.name, right.name) }
        SortField.SIZE -> compareBy { it.size }
        SortField.MODIFIED -> compareBy { it.modified }
        SortField.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.substringAfterLast('.', "") }
    }
    val combined = folderComparator.then(valueComparator)
    return if (sort.direction == SortDirection.ASCENDING) combined
    else folderComparator.then(valueComparator.reversed())
}

/** الاحتياط الرقمي المضمّن (يُستعمل فقط إن غاب ICU — لا يحدث على أندرويد 16). */
private fun compareNatural(left: String, right: String): Int {
    val leftParts = Regex("(\\d+|\\D+)").findAll(left.lowercase(Locale.ROOT)).map { it.value }.toList()
    val rightParts = Regex("(\\d+|\\D+)").findAll(right.lowercase(Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(leftParts.size, rightParts.size)) {
        val a = leftParts[i]; val b = rightParts[i]
        val result = if (a.firstOrNull()?.isDigit() == true && b.firstOrNull()?.isDigit() == true) {
            a.toLongOrNull()?.compareTo(b.toLongOrNull() ?: Long.MAX_VALUE) ?: a.compareTo(b)
        } else a.compareTo(b)
        if (result != 0) return result
    }
    return leftParts.size.compareTo(rightParts.size)
}

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
    val dimensions: Pair<Int, Int>? = if (isImage && !isDirectory) runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }.getOrNull() else null
    val isMedia = MimeTypes.isAudio(coreMime) || coreMime?.startsWith("video/") == true ||
        FileKinds.isVideo(name)
    val duration: Long? = if (isMedia && !isDirectory) runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(path.toString())
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull() else null
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

object ShareIntents {
    fun single(context: Context, entry: FileEntry): Intent? = uriFor(context, entry)?.let { uri ->
        Intent(Intent.ACTION_SEND).apply {
            type = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            attachReadClip(listOf(uri))
        }
    }

    /**
     * ClipData مع منح القراءة: منح FLAG_GRANT_READ_URI_PERMISSION في ACTION_SEND يُطبَّق
     * على ما في ClipData (وData) لا على EXTRA_STREAM وحده في كل الإصدارات/التطبيقات
     * المستقبِلة، فبلا ClipData قد لا يصل المنح إلى المستقبِل. القراءة فقط دائماً.
     */
    fun Intent.attachReadClip(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val clip = android.content.ClipData.newRawUri(null, uris.first())
        uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** FileProvider غير مصدّر مع منح صريح للقراءة فقط (المشاركة). */
    fun uriFor(context: Context, entry: FileEntry): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", entry.path.toFile())
    }.getOrNull()
}

/**
 * فترات مرشح «آخر تعديل». `modifiedAfter` في [SearchSpec] وقت مطلق (epoch ms)،
 * لذا يُحوَّل اختيار الفترة إلى «الآن − المدة» ويُعاد عند عرض الحوار إلى الفترة التي تغطيه (انظر [indexOf]).
 * الفهرس 0 = أي وقت، 1..4 = يوم/أسبوع/شهر/سنة.
 */
object ModifiedPeriod {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val DURATIONS_MS = longArrayOf(DAY_MS, 7 * DAY_MS, 30 * DAY_MS, 365 * DAY_MS)
    private const val ROUNDTRIP_SLACK_MS = 60L * 60 * 1000

    val optionCount: Int get() = DURATIONS_MS.size + 1

    /** الحد الأدنى لوقت التعديل للفهرس المختار، أو null لـ«أي وقت». */
    fun cutoff(index: Int, now: Long): Long? =
        if (index in 1..DURATIONS_MS.size) now - DURATIONS_MS[index - 1] else null

    /**
     * فترة «آخر N يوماً» التي تغطي عمر الحد المطلق: أصغر مدة d يكون عندها
     * `age <= d + ROUNDTRIP_SLACK_MS` (الهامش يسمح بمرور دقائق بين اختيار الفترة
     * وإعادة فتح الحوار)، وإن تجاوز العمر أكبر مدة فآخر فترة. 0 إن لم يكن هناك حد.
     * كان يختار «أقرب» فترة فيظهر عمر 3 أيام «يوماً» رغم أنه خارج اليوم.
     */
    fun indexOf(after: Long?, now: Long): Int {
        if (after == null) return 0
        val age = now - after
        val covering = DURATIONS_MS.indices.firstOrNull { age <= DURATIONS_MS[it] + ROUNDTRIP_SLACK_MS }
        return (covering ?: DURATIONS_MS.lastIndex) + 1
    }
}
