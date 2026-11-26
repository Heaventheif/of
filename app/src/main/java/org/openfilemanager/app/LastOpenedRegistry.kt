package org.openfilemanager.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class LastOpenedRegistry(
    appContext: Context,
    private val scope: CoroutineScope
) {
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // All snapshots are complete strings; conflation is safe because each write
    // supersedes the previous full snapshot. This bounds memory during rapid opens.
    private val writeQueue = Channel<String>(Channel.CONFLATED)
    @Volatile
    private var cachedEntries: List<Entry>? = null

    init {
        scope.launch {
            for (raw in writeQueue) {
                @android.annotation.SuppressLint("ApplySharedPref")
                val ok = prefs.edit().putString(KEY_ENTRIES, raw).commit()
                if (!ok) AppLog.e("recent", "تعذر حفظ سجل الملفات الأخيرة")
            }
        }
    }

    /** عنصر واحد: المسار الكامل وطابع آخر فتح بالميلي ثانية. */
    data class Entry(val path: Path, val lastOpenedAt: Long)

    /**
     * تسجيل فتح ملف: يحدّث الطابع الزمني ويرفع العنصر لمقدمة السجل.
     * الاستدعاء من أي خيط؛ الكتابة على القرص من نطاق IO فقط.
     */
    fun record(path: Path) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val entries = readEntriesLocked()
            val updated = ArrayList<Entry>(entries.size + 1)
            updated.add(Entry(path, now))
            entries.forEach { if (it.path != path) updated.add(it) }
            while (updated.size > MAX_ENTRIES) updated.removeAt(updated.lastIndex)
            writeEntriesLocked(updated)
        }
    }

    /** أحدث الملفات المفتوحة (الأحدث أولاً)؛ قراءة لقطة فقط، آمنة من أي خيط. */
    fun recent(): List<Entry> = synchronized(this) { readEntriesLocked().toList() }

    /** حذف مسار من السجل (بعد حذف الملف أو نقل تعذر تتبعه). */
    fun remove(path: Path) {
        synchronized(this) {
            val entries = readEntriesLocked()
            if (entries.none { it.path == path }) return
            writeEntriesLocked(entries.filterNot { it.path == path })
        }
    }

    /** مسح السجل كله (لا يمس الملفات نفسها). */
    fun clear() {
        synchronized(this) { writeEntriesLocked(emptyList()) }
    }

    // ---------- الترميز: سطر لكل عنصر «طابع|مسار» — بلا مسارات جديدة أسطر إطلاقاً ----------

    private fun readEntriesLocked(): List<Entry> {
        cachedEntries?.let { return it }
        val raw = prefs.getString(KEY_ENTRIES, "") ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val sep = line.indexOf('|')
            if (sep <= 0) return@mapNotNull null
            val stamp = line.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
            val pathText = line.substring(sep + 1)
            if (pathText.isBlank()) return@mapNotNull null
            // Corrupt or manually edited preferences must not crash the home screen.
            val path = runCatching { Paths.get(pathText) }.getOrNull() ?: return@mapNotNull null
            Entry(path, stamp)
        }.sortedByDescending { it.lastOpenedAt }
    }

    private fun writeEntriesLocked(entries: List<Entry>) {
        val snapshot = entries.take(MAX_ENTRIES).toList()
        cachedEntries = snapshot
        val raw = snapshot.joinToString("\n") { "${it.lastOpenedAt}|${it.path}" }
        // One serialized writer receives full snapshots. CONFLATED bounds queued
        // writes and avoids an older concurrent commit overwriting a newer record.
        writeQueue.trySend(raw)
    }

    companion object {
        /** سقف السجل (العرض يحتاج آخر 4-6 مدخلات؛ السجل أوسع قليلاً ليبقى مفيداً). */
        const val MAX_ENTRIES = 50
        private const val PREFS_NAME = "last_opened_files"
        private const val KEY_ENTRIES = "entries"

        /** هل المسار ما يزال قائماً؟ (استدعاء IO فقط — Files.exists). */
        fun exists(entry: Entry): Boolean = runCatching { Files.exists(entry.path) }.getOrDefault(false)
    }
}
