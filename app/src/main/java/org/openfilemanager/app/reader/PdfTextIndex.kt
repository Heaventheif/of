package org.openfilemanager.app.reader

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openfilemanager.app.AppDispatchers
import org.openfilemanager.core.pdf.PdfEncryptedException
import org.openfilemanager.core.pdf.PdfOutlineEntry
import org.openfilemanager.core.pdf.PdfTextDocument

data class PdfHit(val page: Int, val offset: Int, val snippet: String)

/** Raw text of one page plus the (optional) line under the finger: [hlStart, hlEnd) or -1. */
data class PdfPageSelection(val text: String, val hlStart: Int, val hlEnd: Int)

enum class PdfSearchOutcome { DONE, NO_TEXT, UNREADABLE, LIMIT }

/** Outline + text search over the PDF's own text layer (see [PdfTextDocument]); the platform renderer has neither. */
class PdfTextIndex private constructor(private val doc: PdfTextDocument) {

    // Normalised page text (newlines → spaces, offsets preserved), bounded so a huge document cannot exhaust memory.
    private val texts = HashMap<Int, String>()
    private var cachedChars = 0L
    private var sawUnmapped = false

    val pageCount: Int get() = doc.pageCount

    suspend fun outline(): List<PdfOutlineEntry> = withContext(AppDispatchers.io) {
        runCatching { doc.outline() }.getOrDefault(emptyList())
    }

    private fun normalized(page: Int): String {
        synchronized(texts) { texts[page]?.let { return it } }
        val pt = doc.pageText(page)
        if (pt.skippedUnmapped) sawUnmapped = true
        val t = pt.text.replace('\n', ' ')
        synchronized(texts) {
            if (cachedChars + t.length <= MAX_CACHED_CHARS) { texts[page] = t; cachedChars += t.length }
        }
        return t
    }

    /**
     * Scans all pages for [query] (case-insensitive), reporting growing result lists through [onUpdate]
     * (hits so far, pages scanned). Runs off the main thread and stops promptly when cancelled.
     */
    suspend fun search(query: String, onUpdate: (hits: List<PdfHit>, scanned: Int) -> Unit): PdfSearchOutcome =
        withContext(AppDispatchers.io) {
            val q = query.trim().replace(Regex("\\s+"), " ")
            if (q.isEmpty()) return@withContext PdfSearchOutcome.DONE
            val hits = ArrayList<PdfHit>()
            var anyText = false
            var limited = false
            var lastReport = System.nanoTime()
            val total = doc.pageCount
            for (page in 0 until total) {
                currentCoroutineContext().ensureActive()
                val text = runCatching { normalized(page) }.getOrDefault("")
                if (text.isNotBlank()) anyText = true
                var from = 0
                while (!limited) {
                    val at = text.indexOf(q, from, ignoreCase = true)
                    if (at < 0) break
                    if (hits.size >= MAX_HITS) { limited = true; break }
                    hits.add(PdfHit(page, at, snippet(text, at, q.length)))
                    from = at + q.length
                }
                val now = System.nanoTime()
                if (limited || now - lastReport > 150_000_000L || page == total - 1) {
                    lastReport = now
                    onUpdate(hits.toList(), page + 1)
                }
                if (limited) break
            }
            when {
                limited -> PdfSearchOutcome.LIMIT
                !anyText && sawUnmapped -> PdfSearchOutcome.UNREADABLE
                !anyText -> PdfSearchOutcome.NO_TEXT
                else -> PdfSearchOutcome.DONE
            }
        }

    /** Approximate marker for a hit: [top, height] as fractions of the page height, or null. */
    suspend fun band(hit: PdfHit): DoubleArray? = withContext(AppDispatchers.io) {
        runCatching { doc.pageText(hit.page).lineBand(hit.offset) }.getOrNull()
    }

    /** Page text for the select/copy sheet; [yFrac] = press height as a fraction of the page (null = no highlight). */
    suspend fun pageSelection(page: Int, yFrac: Double?): PdfPageSelection? = withContext(AppDispatchers.io) {
        runCatching {
            val pt = doc.pageText(page)
            val r = if (yFrac != null) pt.lineRange(yFrac) else null
            PdfPageSelection(pt.text, r?.get(0) ?: -1, r?.get(1) ?: -1)
        }.getOrNull()
    }

    fun close() { doc.close() }

    private fun snippet(text: String, at: Int, len: Int): String {
        val from = (at - 24).coerceAtLeast(0)
        val to = (at + len + 40).coerceAtMost(text.length)
        return text.substring(from, to).trim()
    }

    companion object {
        private const val MAX_HITS = 1000
        private const val MAX_CACHED_CHARS = 12_000_000L

        /** Null when the structure cannot be read (encrypted, damaged): outline hidden, search says so. */
        suspend fun open(file: File): PdfTextIndex? = withContext(AppDispatchers.io) {
            try {
                PdfTextIndex(PdfTextDocument.open(file))
            } catch (e: PdfEncryptedException) {
                null
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }
}
