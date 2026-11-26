package org.openfilemanager.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import org.openfilemanager.app.ui.components.ApkIcon
import org.openfilemanager.app.ui.components.ApkIconFactory
import org.openfilemanager.app.ui.components.ApkIconKeyer
import org.openfilemanager.app.ui.components.AudioArt
import org.openfilemanager.app.ui.components.AudioArtFactory
import org.openfilemanager.app.ui.components.AudioArtKeyer

/** فئة التطبيق: تُثبَّت قبل أي نشاط — - [AppLog.install]: سجل أخطاء محلي معمى المسارات + معالج الانهيارات. */
class App : Application() {

    /** Manual DI container: created early so activities and services can grab it via [appContainer]. */
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base.applyLanguage())
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        org.openfilemanager.app.imageedit.engine.EditorFonts.init(this)
        AppLog.install(this)
        container.settings.preload()
        installImageLoader()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            enableStrictMode()
        }
    }

    /**
     * ضغط الذاكرة: حين يطلب النظام تقليص الاستهلاك (تطبيق آخر في المقدمة،
     * ذاكرة منخفضة) يُفرَّغ كاش صور Coil في الذاكرة وأقسام المحرّك الأثقل. الكاش على
     * القرص يبقى فتعود الصور المصغّرة سريعاً دون إعادة فك ضغط كل شيء.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        runCatching { Coil.imageLoader(this).memoryCache?.trimMemory(level) }
        org.openfilemanager.app.imageedit.engine.CutEngine.trimMemory()
    }

    /** محمّل الصور مرة واحدة لكل عملية بسياق التطبيق لا النشاط. */
    private fun installImageLoader() {
        Coil.setImageLoader(
            ImageLoader.Builder(this).components {
                add(VideoFrameDecoder.Factory())
                add(AudioArtFactory(), AudioArt::class.java)
                add(ApkIconFactory(), ApkIcon::class.java)
                add(AudioArtKeyer, AudioArt::class.java)
                add(ApkIconKeyer, ApkIcon::class.java)
            }.crossfade(false).build()
        )
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        @Suppress("DEPRECATION")
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
                .penaltyLog()
                .build()
        )
    }
}
