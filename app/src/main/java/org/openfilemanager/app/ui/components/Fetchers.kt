package org.openfilemanager.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coil models: the cover embedded inside an audio file, and the real icon inside an APK. */
data class AudioArt(val path: String, val modified: Long)
data class ApkIcon(val path: String, val modified: Long)

private class AudioArtFetcher(private val data: AudioArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        // استخدام MediaProbe.embeddedArtwork الموحّد.
        val bytes = org.openfilemanager.app.MediaProbe.embeddedArtwork(java.nio.file.Paths.get(data.path))
            ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        // Sample by the longest side so extreme aspect ratios cannot blow up memory
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= 512) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@withContext null
        DrawableResult(BitmapDrawable(options.context.resources, bmp), isSampled = sample > 1, dataSource = DataSource.DISK)
    }
}

private class ApkIconFetcher(private val data: ApkIcon, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        runCatching {
            val pm = options.context.packageManager
            val info = pm.getPackageArchiveInfo(data.path, 0) ?: return@runCatching null
            val ai = info.applicationInfo ?: return@runCatching null
            ai.sourceDir = data.path
            ai.publicSourceDir = data.path
            DrawableResult(ai.loadIcon(pm), isSampled = false, dataSource = DataSource.DISK)
        }.getOrNull()
    }
}

class AudioArtFactory : Fetcher.Factory<AudioArt> {
    override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher = AudioArtFetcher(data, options)
}

class ApkIconFactory : Fetcher.Factory<ApkIcon> {
    override fun create(data: ApkIcon, options: Options, imageLoader: ImageLoader): Fetcher = ApkIconFetcher(data, options)
}

val AudioArtKeyer = Keyer<AudioArt> { d, _ -> "audio:${d.path}:${d.modified}" }
val ApkIconKeyer = Keyer<ApkIcon> { d, _ -> "apk:${d.path}:${d.modified}" }
