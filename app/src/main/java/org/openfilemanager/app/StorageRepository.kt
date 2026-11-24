package org.openfilemanager.app

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import org.openfilemanager.core.NameRules
import org.openfilemanager.core.SafeRename

/** جذر تخزين يُعرض للمستخدم: الداخلي أو بطاقة SD أو USB OTG. */
data class StorageRoot(val path: Path, val label: String, val isRemovable: Boolean)

/**
 * الموقع الحالي في المستعرض. NIO صرف (NioBackend فوق `java.nio.file.Path`)؛
 * الوصول الشامل عبر صلاحية MANAGE_EXTERNAL_STORAGE على Android 11+؛ عند غيابها
 * يُستخدم تخزين التطبيق الخاص أو الملفات التي يشاركها المستخدم صراحةً.
 */
data class BrowserLocation(val path: Path) {
    val directory: File get() = path.toFile()
}

/** عنصر مجلد: بيانات وصفية فقط والمسار يُشتق منه عند الحاجة. */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val path: Path
) {
    val localFile: File get() = path.toFile()
}

/**
 * طبقة التخزين: قراءة فعالة بنداء خصائص واحد لكل عنصر،
 * وحذف بلا اتباع الروابط الرمزية، وكشف وحدات التخزين عبر StorageManager عند توفر API 30+.
 */
class StorageRepository(private val context: Context) {

    /** Full file-tree access is available only through the Android 11+ special access. */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** مدير التخزين لمراقبة تركيب/فصل الوسائط؛ null إن لم يتوفر. */
    fun storageManager(): StorageManager? =
        runCatching { context.getSystemService(StorageManager::class.java) }.getOrNull()

    /** موضع البداية: جذر الوحدة الأساسية مع الصلاحية، وإلا تخزين التطبيق (وضع محدود). */
    fun defaultLocation(): BrowserLocation =
        if (hasAllFilesAccess()) BrowserLocation(primaryRoot().path)
        else BrowserLocation(context.filesDir.toPath())

    fun appStorage(): BrowserLocation = BrowserLocation(context.filesDir.toPath())

    /**
     * وحدات التخزين التي ركّبها النظام فعلياً. الوسائط غير المركّبة لا تُدعم
     *: لا مجلد لها أصلاً فلا تظهر، ويعرض النظام نفسه سبب عدم التركيب.
     */
    fun roots(): List<StorageRoot> {
        if (!hasAllFilesAccess()) {
            return listOf(StorageRoot(context.filesDir.toPath(), context.getString(R.string.storage_app_limited), false))
        }
        val volumes: List<StorageVolume> = runCatching {
            context.getSystemService(StorageManager::class.java)?.storageVolumes ?: emptyList()
        }.getOrDefault(emptyList())
        val result = volumes.mapNotNull { volume ->
            volume.directory?.let { dir ->
                StorageRoot(dir.toPath(), labelOf(volume), volume.isRemovable)
            }
        }
        return result.ifEmpty { listOf(primaryRoot()) }
    }

    private fun primaryRoot(): StorageRoot =
        StorageRoot(Environment.getExternalStorageDirectory().toPath(), context.getString(R.string.storage_internal), false)

    private fun labelOf(volume: StorageVolume): String =
        runCatching { volume.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: context.getString(if (volume.isRemovable) R.string.storage_removable else R.string.storage_internal)

    /**
     * Android/data وAndroid/obb محجوبتان نظامياً حتى مع الصلاحية الكاملة:
     * نعرض سبباً واضحاً بدل استثناء غامض، ولا نحاول التحايل.
     */
    fun blockedReason(path: Path): String? {
        val absolute = path.toAbsolutePath().normalize().toString()
        val marker = "/Android/"
        val index = absolute.lastIndexOf(marker)
        if (index < 0) return null
        val rest = absolute.substring(index + marker.length)
        return when {
            rest == "data" || rest.startsWith("data/") ||
                rest == "obb" || rest.startsWith("obb/") ->
                context.getString(R.string.storage_blocked_reason)
            else -> null
        }
    }

    /**
     * قراءة المجلد بنداء خصائص واحد لكل عنصر (لا listFiles ثم length لكل ملف).
     * (F-11/F-15) يعيد null عند تعذر التعداد (مجلد غير موجود/رفض وصول/I/O)
     * تمييزاً عن قائمة فارغة حقيقية — كان getOrDefault(emptyList()) يجعل
     * المجلد الفاشل يبدو فارغاً بصمت. القائمة لا تتبع الروابط الرمزية.
     *
     * (تحديث) runCatching كان يلتقط كل Throwable فيبتلع OutOfMemoryError
     * (يظهر «مجلد غير مقروء» بدل سقوط واضح) — الآن IOException فقط؛ ما
     * يتجاوز I/O يُرمى كما هو.
     */
    fun list(location: BrowserLocation): List<FileEntry>? {
        val dir = location.path.toAbsolutePath().normalize()
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val entries = ArrayList<FileEntry>(256)
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (path in stream) {
                    val attrs = try {
                        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (e: java.io.IOException) {
                        continue // عنصر تعذّرت قراءة خصائصه: يُتخطى، البقية تظهر
                    }
                    // لا نتبع الروابط الرمزية في قائمة الملفات؛ فتح الرابط يحتاج قرارًا صريحًا منفصلًا.
                    if (attrs.isSymbolicLink) continue
                    entries.add(
                        FileEntry(
                            name = path.fileName.toString(),
                            isDirectory = attrs.isDirectory,
                            size = if (attrs.isDirectory) 0L else attrs.size(),
                            modified = runCatching { attrs.lastModifiedTime().toMillis() }.getOrDefault(0L),
                            path = path
                        )
                    )
                }
            }
        } catch (e: java.io.IOException) {
            return null // تعذر التعداد نفسه: يميَّز عن قائمة فارغة حقيقية (F-11)
        }
        return entries
    }

    fun child(location: BrowserLocation, entry: FileEntry): BrowserLocation = BrowserLocation(entry.path)

    fun parent(location: BrowserLocation): BrowserLocation? =
        location.path.parent?.let { BrowserLocation(it) }

    private fun safeChild(parent: Path, rawName: String): Path? {
        val name = rawName.trim()
        if (NameRules.problem(name, strictFat = true) != null) return null
        val normalizedParent = parent.toAbsolutePath().normalize()
        val target = normalizedParent.resolve(name).normalize()
        return target.takeIf { it.parent == normalizedParent && it.fileName.toString() == name }
    }

    fun createFolder(location: BrowserLocation, name: String): Boolean {
        val target = safeChild(location.path, name) ?: return false
        return runCatching { Files.createDirectory(target); true }.getOrDefault(false)
    }

    /** إنشاء ملف فارغ جديد: يفشل إن كان الاسم موجوداً — لا استبدال صامت. */
    fun createFile(location: BrowserLocation, name: String): Boolean {
        val target = safeChild(location.path, name) ?: return false
        return runCatching { Files.createFile(target); true }.getOrDefault(false)
    }

    fun rename(entry: FileEntry, name: String): Boolean =
        runCatching { SafeRename.rename(entry.path, name); true }.getOrDefault(false)

    companion object {
        /**
         * وحدتان مختلفتان إذا اختلف `st_dev`. عند تعذر القياس نعيد false:
         * خطأ الحكم «مختلف» يكلف نسخاً موثقاً (أبطأ لكنه سليم)، والعكس يفشل النقل الذري.
         */
        fun sameVolume(a: Path, b: Path): Boolean = try {
            Os.stat(a.toString()).st_dev == Os.stat(b.toString()).st_dev
        } catch (e: Exception) {
            false
        }

        /**
         * Placeholder (F15): `posix_fadvise(DONTNEED)` ليس في SDK العام، وJNI لأجله
         * وحده لا يبرر كلفة صيانة كود أصلي هنا. القيد المترتب على ذلك بوضوح:
         * إعادة قراءة الوجهة في [AtomicIO.moveVerified] (تحقق SHA-256) قد تأتي
         * من page cache لا من الوسيط، فالتحقق يظل قائماً على ما كُتب فعلاً
         * في مسار النواة لكنه ليس دليلاً فيزيائياً على الوصول للقرص — الدليل
         * الفيزيائي هو `fsync` (FileChannel.force) في [AtomicIO] نفسه قبل النشر.
         * لا تعتمد على هذه الدالة كدليل مادي؛ تُترك فارغة عمداً بدل حذفها
         * حتى يبقى موضع الاستدعاء جاهزاً إن أُتيح fadvise لاحقاً.
         */
        fun dropPageCache(@Suppress("UNUSED_PARAMETER") file: Path) {
        }
    }
}
