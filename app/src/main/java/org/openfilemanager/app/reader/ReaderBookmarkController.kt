package org.openfilemanager.app.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** منسق الإشارات المرجعية المشترك بين قارئي PDF و DOCX. */
class ReaderBookmarkController(
    private val docId: String,
    private val format: ReaderFormat,
    private val store: ReaderStore,
    private val scope: CoroutineScope,
) {
    /** يضيف إشارة في الموضع (a, f). يُطلق على خيط خلفي عبر scope. */
    fun add(title: String, a: Int, f: Float) {
        scope.launch { store.addBookmark(docId, format, title, a, f) }
    }

    fun remove(b: Bookmark) {
        scope.launch { store.removeBookmark(docId, format, b.id) }
    }

    fun rename(b: Bookmark, title: String) {
        scope.launch { store.renameBookmark(docId, format, b.id, title) }
    }
}
