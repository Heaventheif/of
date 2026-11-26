package org.openfilemanager.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.openfilemanager.core.FileOpJournal

/** Manual dependency container. */
class AppContainer(appContext: Context) {

    private val appContext: Context = appContext.applicationContext

    /**
     * نطاق مشترك لمفردات الحاوية التي تطلق كوروتينات تعيش مع العملية
     * (جمع نتائج OpsCenter في ClipboardStore) — لا viewModelScope ولا نطاق
     * خدمة يموت مع صاحبه.
     */
    private val containerScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    val processScope: CoroutineScope get() = containerScope

    /** App settings (DataStore + synchronous snapshot): single instance, same as AppSettings.get. */
    val settings: AppSettings by lazy { AppSettings.get(appContext) }

    /** Operations bridge between the service and UI: single instance, read-only flows. */
    val opsCenter: OpsCenter by lazy { OpsCenter(appContext) }

    /**
     * حافظة اللصق على مستوى العملية: كانت أعضاء في ViewModel
     * فتُفقد عند قتل العملية. Singleton هنا ينجو، ويجمع نتائج النقل بنفسه.
     */
    val clipboard: ClipboardStore by lazy { ClipboardStore(opsCenter, containerScope) }

    /**
     * أرشيف مفرد لكل العملية: كان يُنشأ لكل استدعاء (خدمة ومعاينة
     * ViewModel) — عديم الحالة الجوهرية فلا مبرر للإنشاء المتكرر.
     */
    val archiveService: ArchiveService by lazy { ArchiveService(appContext) }

    val lastOpened: LastOpenedRegistry by lazy { LastOpenedRegistry(appContext, containerScope) }

    /** Folders pinned to the side menu. */
    val pinned: org.openfilemanager.app.ui.PinnedFolders by lazy { org.openfilemanager.app.ui.PinnedFolders(appContext) }

    /** Reader (PDF/DOCX): reading positions, bookmarks and reader-only preferences (separate from the text editor's). */
    val readerStore: org.openfilemanager.app.reader.ReaderStore by lazy { org.openfilemanager.app.reader.ReaderStore(appContext) }

    /** Single operation journal (delegates to [OpsCenter.journal] so the identity stays unique). */
    val journal: FileOpJournal get() = opsCenter.journal

    /**
     * طبقة التخزين الموحدة: جذور الوحدات والمواقع الافتراضية وقراءة
     * المجلدات — كان مفرداً معمارياً بلا مستهلك فعلي بينما المتصفح والرئيسية
     * يتاجران File.listFiles مباشرة؛ الآن كلاهما يمر من هنا.
     */
    val storage: StorageRepository by lazy { StorageRepository(appContext) }
}

/** Access the dependency container from any Context (activity/service/application). */
val Context.appContainer: AppContainer
    get() = (applicationContext as? App)?.container ?: fallbackContainer(applicationContext)

/**
 * احتياط حين لا يكون applicationContext هو [App] (تطبيق مخصص،
 * أدوات مثل Macrobenchmark): كان cast مباشر يرمي ClassCastException. حاوية واحدة
 * لكل عملية تُبنى عند أول طلب.
 */
private val fallbackLock = Any()

@Volatile
private var fallbackContainerInstance: AppContainer? = null

private fun fallbackContainer(appContext: Context): AppContainer =
    fallbackContainerInstance ?: synchronized(fallbackLock) {
        fallbackContainerInstance ?: AppContainer(appContext).also { fallbackContainerInstance = it }
    }
