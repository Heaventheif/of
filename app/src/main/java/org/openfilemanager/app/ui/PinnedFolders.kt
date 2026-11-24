package org.openfilemanager.app.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.openfilemanager.app.AppDispatchers

private val Context.pinnedDataStore: DataStore<Preferences> by preferencesDataStore(name = "pinned_folders")

/** Folders the user pinned; shown in the side menu. Stored as absolute paths, in the order they were pinned. */
class PinnedFolders(context: Context) {
    private val ds = context.applicationContext.pinnedDataStore
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    val paths: Flow<List<String>> = ds.data.map { decode(it[KEY]) }

    fun pin(path: String) = update { if (path in it) it else (it + path).takeLast(MAX) }

    fun unpin(path: String) = update { list -> list.filterNot { it == path } }

    private fun update(transform: (List<String>) -> List<String>) {
        scope.launch {
            ds.edit { p ->
                val next = transform(decode(p[KEY]))
                if (next.isEmpty()) p.remove(KEY) else p[KEY] = JSONArray(next).toString()
            }
        }
    }

    private fun decode(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY = stringPreferencesKey("paths")
        const val MAX = 50
    }
}
