package org.openfilemanager.app

import android.content.Context
import org.openfilemanager.core.PathRedactor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** سجل أخطاء محلي فقط: لا خدمات تقارير خارجية، ولا شيء يخرج من الجهاز (لا صلاحية INTERNET أصلاً). */
object AppLog {
    private const val MAX_LOG_FILES = 5
    private const val MAX_LOG_FILE_BYTES = 1L * 1024L * 1024L
    private const val MAX_ENTRY_CHARS = 16_384
    private const val DATE_FORMAT = "yyyy-MM-dd"

    @Volatile
    private var logsDir: Path? = null

    /**
     * مرآة خارجية لآخر انهيار: Android/data/<pkg>/files/crash/last-crash.log —
     * قابلة للقراءة عبر USB أو أي مدير ملفات بلا صلاحيات ولا جذر (المسار الداخلي
     * filesDir لا يُقرأ إلا بجذر). هذا ما يجعل تشخيص تعطل الجهاز ممكناً.
     */
    @Volatile
    private var mirrorDir: Path? = null

    /** يُثبَّت مرة واحدة من فئة التطبيق قبل أي نشاط. */
    fun install(context: Context) {
        val dir = Paths.get(context.filesDir.path, "logs")
        runCatching { Files.createDirectories(dir) }
        logsDir = dir
        runCatching {
            mirrorDir = context.getExternalFilesDir(null)?.toPath()?.resolve("crash")
            mirrorDir?.let { Files.createDirectories(it) }
        }
        prune()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("F", "crash", "استثناء غير معالج على الخيط ${thread.name}: ${error.javaClass.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)
    fun i(tag: String, message: String) = write("I", tag, message, null)

    @Synchronized
    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val dir = logsDir ?: return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val unboundedBody = buildString {
            append(stamp).append(' ').append(level).append('/').append(tag).append(": ")
            append(PathRedactor.redact(message))
            error?.let { append('\n').append(PathRedactor.redact(it.stackTraceToString())) }
        }
        val body = if (unboundedBody.length <= MAX_ENTRY_CHARS) unboundedBody else
            unboundedBody.take(MAX_ENTRY_CHARS) + "\n[log entry truncated]"
        runCatching {
            val current = dir.resolve("app-${LocalDate.now().format(DateTimeFormatter.ofPattern(DATE_FORMAT))}.log")
            val bytes = (body + "\n").toByteArray(Charsets.UTF_8)
            if (Files.exists(current) && Files.size(current) + bytes.size > MAX_LOG_FILE_BYTES) rotate(current)
            Files.write(
                current,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
        if (level == "F") {
            runCatching {
                val mDir = mirrorDir ?: return@runCatching
                Files.write(
                    mDir.resolve("last-crash.log"),
                    (body + "\n---\n").toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            }
        }
        prune()
    }

    /** Rotates the daily log before it exceeds the size cap. Keeps four older slices. */
    private fun rotate(current: Path) {
        for (index in MAX_LOG_FILES - 2 downTo 1) {
            val source = current.resolveSibling(current.fileName.toString().removeSuffix(".log") + "-$index.log")
            val target = current.resolveSibling(current.fileName.toString().removeSuffix(".log") + "-${index + 1}.log")
            if (Files.exists(source)) {
                Files.deleteIfExists(target)
                Files.move(source, target)
            }
        }
        val first = current.resolveSibling(current.fileName.toString().removeSuffix(".log") + "-1.log")
        Files.deleteIfExists(first)
        if (Files.exists(current)) Files.move(current, first)
    }

    /** يبقي آخر [MAX_LOG_FILES] ملفات فقط. */
    private fun prune() {
        val dir = logsDir ?: return
        runCatching {
            val files = Files.list(dir).use { stream ->
                val out = ArrayList<Path>()
                stream.filter { it.fileName.toString().startsWith("app-") }.forEach { out.add(it) }
                out.sortedWith(compareByDescending<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                    .thenByDescending { it.fileName.toString() })
            }
            files.drop(MAX_LOG_FILES).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
