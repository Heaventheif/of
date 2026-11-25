package org.openfilemanager.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.theme.OfmTheme

/** The activity is the ViewModel owner; screens fetch the same instances through this. */
val LocalActivityOwner = androidx.compose.runtime.staticCompositionLocalOf<ViewModelStoreOwner> { error("no owner") }

/** The single entry point of the app (launcher, "open with" and "share" all land here). */
class OfmActivity : ComponentActivity() {
    private val nav: NavViewModel by viewModels()
    private val browser: BrowserViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(org.openfilemanager.app.LanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.openfilemanager.app.ui.theme.ThemeManager.load(this)
        // Transparent bars; icon color follows the *app* theme (not only the system one).
        // Re-applied whenever the appearance changes, see the LaunchedEffect in setContent.
        applyBarStyle()
        // (F-16) Coil.setImageLoader انتقل إلى App.onCreate: كان يُبنى هنا في كل
        // إنشاء للنشاط فتعاد بناء الكاشات عند التدوير/تغيير اللغة ويبقى القديم بلا إغلاق.
        setContent {
            // Layout direction is always RTL, whatever the app language is
            CompositionLocalProvider(
                LocalActivityOwner provides this,
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl
            ) {
                val dark = org.openfilemanager.app.ui.theme.isOfmDark()
                androidx.compose.runtime.LaunchedEffect(dark) { applyBarStyle(dark) }
                OfmTheme { PermissionGate { OfmApp() } }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    private fun applyBarStyle(dark: Boolean = when (org.openfilemanager.app.ui.theme.ThemeManager.mode) {
        org.openfilemanager.app.ui.theme.ThemeManager.DARK -> true
        org.openfilemanager.app.ui.theme.ThemeManager.LIGHT -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onStop() {
        super.onStop()
        // (F-17) flushBlocking يعيد نجاح التفريغ فعلاً: انتهاء المهلة يُسجل
        // (حالة قابلة للملاحظة في AppSettings) ولا يبدو حاجزاً مؤكداً.
        val flushed = org.openfilemanager.app.AppSettings.get(this).flushBlocking()
        if (!flushed) {
            org.openfilemanager.app.AppLog.i("settings", "لم تُفرَّغ كتابات الإعدادات خلال مهلة الإيقاف؛ ستُكتب لاحقاً")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(incoming: Intent?) {
        val uris = incoming?.let(IncomingFiles::uris).orEmpty()
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) { runCatching { IncomingFiles.stage(this@OfmActivity, uris) } }
            staged.onSuccess { if (it.isNotEmpty()) nav.openIncoming(this@OfmActivity, it, browser) }
                .onFailure { browser.notify(getString(R.string.ui_err_incoming)) }
        }
    }
}
