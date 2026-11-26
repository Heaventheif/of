package org.openfilemanager.app

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.nio.file.Path

/** مساعد موحّد لاستكشاف خصائص الصور والوسائط من القرص دون فكها كاملاً. */
object MediaProbe {

    /**
     * أبعاد الصورة (width, height) من قراءة الحدود فقط — لا تُحمّل البكسلات.
     * null إن لم تكن صورة صالحة. مطابقة لسلوك `FileFeatures.properties()` السابق.
     */
    fun imageBounds(path: Path): Pair<Int, Int>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }.getOrNull()

    /**
     * مدة الوسائط بالميلي ثانية أو null. كانت مكررة في FileFeatures (و MusicPlaybackService
     * يقرأ وسوم متعددة فلا يصلح لهذا المساعد).
     */
    fun mediaDuration(path: Path): Long? = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(path.toString())
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()

    /**
     * الصورة المضمّنة في ملف وسائط (غلاف ألبوم) أو null. كانت في Fetchers.
     */
    fun embeddedArtwork(path: Path): ByteArray? = runCatching {
        MediaMetadataRetriever().use { r -> r.setDataSource(path.toString()); r.embeddedPicture }
    }.getOrNull()
}
