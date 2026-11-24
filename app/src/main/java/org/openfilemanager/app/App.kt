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

/**
 * فئة التطبيق: تُثبَّت قبل أي نشاط —
 * - [AppLog.install]: سجل أخطاء محلي معمى المسارات + معالج الانهيارات.
 * - محمّل صور Coil مفرد للعملية (F-16): كان يُبنى في onCreate لكل نشاط
 *   فتعاد بناء الكاشات عند التدوير/تغيير اللغة/الاستعادة ويبقى القديم بلا
 *   إغلاق واضح — الإعداد العالمي مرة واحدة في Application onCreate.
 * - StrictMode في بناء debug فقط: كشف تسريبات الكائنات القابلة للإغلاق
 *   والتسجيلات والأنشطة وانكشاف file:// على الشبكة أو في النوايا، وقراءات
 *   القرص على الخيط الرئيسي. كل السياسات بـ penaltyLog لا penaltyDeath —
 *   الكشف كامل لكن الفحص لا يقتل التطبيق أبداً.
 */
class App : Application() {

    /** Manual DI container: created early so activities and services can grab it via [appContainer]. */
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LanguageManager.wrap(base))
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

    /** (F-16) محمّل الصور مرة واحدة لكل عملية بسياق التطبيق لا النشاط. */
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
