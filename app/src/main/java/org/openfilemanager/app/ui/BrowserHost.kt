package org.openfilemanager.app.ui

/** واجهة مضيف مشتركة بين Controllers التي تعمل داخل BrowserViewModel. */
interface BrowserHost {
    /** سياق التطبيق — لاستعماله في CoreMessageResolver.resolve. */
    fun appContext(): android.content.Context

    // ---- state access ----
    fun updateState(transform: (BrowserUiState) -> BrowserUiState)
    fun emit(event: UiEvent)

    // ---- messages ----
    fun say(text: String)
    fun sayErr(text: String)
    fun sayInfo(text: String)
    fun sayResult(ok: Boolean, resId: Int)
    fun str(resId: Int, vararg args: Any): String

    // ---- navigation / listing ----
    fun reloadListing()
    fun navigateTo(location: Location, force: Boolean)
    fun clearSelection()
}
