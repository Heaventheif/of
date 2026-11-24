package org.openfilemanager.app.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.core.BoundedLruCache

sealed interface PdfOpenResult {
    class Ok(val engine: PdfEngine) : PdfOpenResult
    data object Password : PdfOpenResult
    data object Corrupt : PdfOpenResult
    data object Empty : PdfOpenResult
    data object Unreadable : PdfOpenResult
}

/**
 * Rendering side of the PDF reader, on the platform [PdfRenderer].
 *
 * PdfRenderer allows one open page at a time, so every access goes through [lock]; page sizes are scanned
 * in the background and bitmaps live in a size-bounded LRU. Nothing renders the whole document at once.
 */
class PdfEngine private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    val pageCount: Int = renderer.pageCount

    private val lock = Mutex()
    private var closed = false
    private val closeScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    /** width / height of each page; the scan fills real values, until then the first page's ratio stands in. */
    @Volatile
    var aspects: FloatArray = FloatArray(pageCount)
        private set

    private data class Key(val page: Int, val width: Int)

    private val cache = BoundedLruCache<Key, Bitmap>(cacheBudget()) { _, bmp -> bmp.allocationByteCount.toLong() }

    /** Reads page sizes one page at a time (releasing the lock between pages so renders can interleave). */
    suspend fun scanSizes(onProgress: (FloatArray) -> Unit) = withContext(AppDispatchers.io) {
        val out = FloatArray(pageCount)
        var lastReport = 0L
        for (i in 0 until pageCount) {
            val ratio = lock.withLock {
                if (closed) return@withContext
                runCatching { renderer.openPage(i).use { it.width.toFloat() / it.height.toFloat() } }.getOrDefault(DEFAULT_ASPECT)
            }
            out[i] = if (ratio.isFinite() && ratio > 0.05f && ratio < 20f) ratio else DEFAULT_ASPECT
            val now = System.nanoTime()
            if (i == 0 || i == pageCount - 1 || now - lastReport > 250_000_000L) {
                lastReport = now
                val snapshot = out.copyOf()
                // Pages not scanned yet borrow the first page's ratio so list heights are sane meanwhile.
                for (j in i + 1 until snapshot.size) snapshot[j] = out[0]
                aspects = snapshot
                onProgress(snapshot)
            }
        }
    }

    fun cached(page: Int, width: Int): Bitmap? = cache.get(Key(page, width))?.takeUnless { it.isRecycled }

    /** Renders [page] at [widthPx] (height follows the page's own ratio). Cancellable while waiting for the lock. */
    suspend fun render(page: Int, widthPx: Int): Bitmap? {
        cached(page, widthPx)?.let { return it }
        return withContext(AppDispatchers.io) {
            lock.withLock {
                if (closed) return@withLock null
                cached(page, widthPx)?.let { return@withLock it }
                try {
                    renderer.openPage(page).use { p ->
                        var w = widthPx.coerceAtLeast(1)
                        var h = (w.toLong() * p.height / p.width).toInt().coerceAtLeast(1)
                        // Cap pixels per bitmap: very high zoom trades sharpness for staying inside memory.
                        if (w.toLong() * h > MAX_PIXELS) {
                            val k = Math.sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h))
                            w = (w * k).toInt().coerceAtLeast(1); h = (h * k).toInt().coerceAtLeast(1)
                        }
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(Key(page, widthPx), bmp)
                        bmp
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    cache.trim(0L)
                    null
                }
            }
        }
    }

    fun trimMemory() { cache.trim(0L) }

    fun close() {
        closeScope.launch {
            // Take the lock so the renderer is never closed in the middle of a render or a size scan.
            lock.withLock {
                if (!closed) {
                    closed = true
                    cache.trim(0L)
                    runCatching { renderer.close() }
                    runCatching { pfd.close() }
                }
            }
            closeScope.cancel()
        }
    }

    companion object {
        private const val DEFAULT_ASPECT = 0.707f
        private const val MAX_PIXELS = 10_000_000L

        private fun cacheBudget(): Long {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 6).coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)
        }

        /** Opens [file]; never throws: failures come back as a typed result for the error screen. */
        suspend fun open(file: File): PdfOpenResult = withContext(AppDispatchers.io) {
            if (!file.exists()) return@withContext PdfOpenResult.Unreadable
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                if (r.pageCount <= 0) {
                    r.close(); pfd.close()
                    PdfOpenResult.Empty
                } else PdfOpenResult.Ok(PdfEngine(pfd, r))
            } catch (e: SecurityException) {
                runCatching { pfd?.close() }
                PdfOpenResult.Password
            } catch (e: java.io.FileNotFoundException) {
                runCatching { pfd?.close() }
                PdfOpenResult.Unreadable
            } catch (e: IOException) {
                runCatching { pfd?.close() }
                PdfOpenResult.Corrupt
            } catch (e: Exception) {
                runCatching { pfd?.close() }
                PdfOpenResult.Corrupt
            }
        }
    }
}
