package org.openfilemanager.core

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** تحديد ما يُستخرج: [EntryFilter.ALL] = كل العناصر؛ وإلا فالعناصر
 * المحددة فقط، وتحديد مجلد يُدخل كل ما تحته. الأسماء كما تظهر في المعاينة ([ArchiveInspector]). */
fun interface EntryFilter {
    fun matches(entryName: String): Boolean

    companion object {
        /** كل العناصر (السلوك التاريخي لل.extract). */
        val ALL: EntryFilter = EntryFilter { true }

        /** العناصر المحددة فقط: تطابق الاسم نفسه أو كل ما دخل مجلد محدد. */
        fun only(selected: Set<String>): EntryFilter = EntryFilter { name ->
            val clean = normalizeSelectedName(name)
            selected.any { sel ->
                val s = normalizeSelectedName(sel)
                clean == s || clean.startsWith("$s/")
            }
        }

        /**
         * تحديد ملفات بعينها من واجهة المستخدم. لا يوسّع اسم الملف إلى كل
         * المسارات التي تبدأ به؛ ذلك السلوك خاص بتحديد المجلدات في only().
         */
        fun exactOnly(selected: Set<String>): EntryFilter {
            val normalized = selected.map(::normalizeSelectedName).toSet()
            return EntryFilter { name -> normalizeSelectedName(name) in normalized }
        }

        private fun normalizeSelectedName(name: String): String =
            name.replace('\\', '/').trimEnd('/')
    }
}

/** مدخل أرشيف خبيث أو غير صالح (مسار خارج الجذر، اسم سيئ، تكرار...). يوقف الاستخراج كله. */
class UnsafeEntryException(message: String) : IOException(message)

/** تجاوز حد من حدود الحماية (عدد العناصر، نسبة الضغط، الحجم الكلي). */
class ArchiveLimitException(message: String) : IOException(message)

data class ExtractLimits(
    val maxEntries: Int = 100_000,
    /** أقصى نسبة بين البايتات المكتوبة والبايتات المقروءة من الأرشيف للعنصر الواحد. */
    val maxRatio: Long = 200,
    /** يُحسب من StatFs في طبقة أندرويد. */
    val maxTotalBytes: Long = Long.MAX_VALUE,
    val maxDepth: Int = 64,
    val xzMemoryLimitKb: Int = 256 * 1024,
    /** هامش يمنع رفض العناصر الصغيرة المشروعة بسبب قراءة المخزن المؤقت المسبقة. */
    val ratioSlackBytes: Long = 1L shl 20,
    /**
     * أقصى نسبة تراكمية: إجمالي البايتات المكتوبة ÷ إجمالي البايتات المقروءة من الأرشيف كله.
     * يسدّ ثغرة الحد لكل ملف وحده (هامش [ratioSlackBytes] يتكرر لكل ملف صغير).
     */
    val maxTotalRatio: Long = maxRatio
) {
    companion object {
        /** (مراجعة 0.8.6-#3) حدود مخفَّفة بقرار صريح من المستخدم (الإعدادات) لملفات
         *  مشروعة شديدة الانضغاط (صور أقراص مفرّغة، سجلات ضخمة) كانت تُرفض عند 200:1.
         *  ما زالت تسدّ القنابل الشائعة (نسب ملايين إلى واحد). */
        fun relaxed(maxTotalBytes: Long): ExtractLimits = ExtractLimits(
            maxTotalBytes = maxTotalBytes,
            maxRatio = 2000,
            maxTotalRatio = 2000,
            ratioSlackBytes = 64L * 1024 * 1024
        )
    }
}

data class ExtractReport(
    val finalPath: Path,
    /** عدد العناصر المقروءة من الأرشيف (بما فيها المجاهدات والروابط). */
    val entries: Int,
    val bytes: Long,
    /** عناصر تُجوهلت: روابط رمزية/صلبة وملفات أجهزة. لا تُنشأ أبداً. */
    val skipped: List<String>,
    /** عدد الملفات المكتوبة فعلاً على القرص. */
    val written: Int = 0
)

/** حلّ آمن لاسم مدخل الأرشيف داخل جذر الاستخراج. */
object SafeResolve {
    private val WIN_DRIVE = Regex("^[A-Za-z]:")

    fun resolve(root: Path, entryName: String, maxDepth: Int = 64): Path {
        if (entryName.isEmpty() || '\u0000' in entryName) throw UnsafeEntryException("bad entry name")
        val clean = entryName.replace('\\', '/')
        if (clean.startsWith("/") || WIN_DRIVE.containsMatchIn(clean)) throw UnsafeEntryException("absolute path: $entryName")
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) throw UnsafeEntryException("empty path: $entryName")
        if (parts.any { it == ".." }) throw UnsafeEntryException("path traversal: $entryName")
        if (parts.size > maxDepth) throw UnsafeEntryException("path too deep: $entryName")
        parts.forEach { part ->
            NameRules.problem(part, strictFat = false)?.let { throw UnsafeEntryException("invalid component ($it): $entryName") }
        }
        val base = root.normalize()
        var target = base
        for (part in parts) target = target.resolve(part)
        val normalized = target.normalize()
        if (normalized == base || !normalized.startsWith(base)) throw UnsafeEntryException("Zip Slip: $entryName")
        return normalized
    }

    /**
     * علامة جذر الأرشيف (5.1): أسماء مثل `""` و`"."` و`"./"` و`"././"` تعني
     * الجذر نفسه لا عنصراً — `tar -czf x.tgz .` يكتبها قبل كل شيء وكانت
     * تُمرَّر إلى [resolve] فترمي "empty path" فيفشل الأرشيف كله. الفحص في
     * المستدعيين (المستخرج والمعاين) لا داخل resolve: سلوك resolve نفسه
     * (رفض المسار الفارغ) محفوظ كما تختبره OpRecoveryTest/ArchiveExtractorTest.
     */
    fun isRootMarker(entryName: String): Boolean {
        if ('\u0000' in entryName) return false
        val clean = entryName.replace('\\', '/')
        if (clean.startsWith("/") || WIN_DRIVE.containsMatchIn(clean)) return false
        return clean.split('/').all { it.isEmpty() || it == "." }
    }
}

/**
 * استخراج ZIP وTAR وTAR.GZ وTAR.XZ وGZ وXZ بحدود حماية.
 * يُستخرج إلى مجلد مؤقت `.fm-extract-*` في مجلد الوجهة نفسه ثم يُنقل إلى الوجهة النهائية
 * دون استبدال عنصر موجود عند النجاح،
 * والفشل أو الإلغاء يحذف المؤقت. لا كتابة فوق ملف موجود.
 * [charset] لأسماء ZIP وTAR (UTF-8 افتراضياً، وWindows-1256 بتأكيد المستخدم للأرشيفات العربية القديمة).
 */
class ArchiveExtractor(
    private val limits: ExtractLimits = ExtractLimits(),
    private val charset: Charset = Charsets.UTF_8
) {
    /**
     * (مراجعة 0.8.6-#2) هل نظام الملفات تحت [dir] حساس لحالة الأحرف؟ نشء ملفين
     * يختلفان في الحالة فقط: النجاح على ext4/f2fs، والفشل بـ FileAlreadyExists
     * على FAT/exFAT. أي إخفاق آخر يعامل بقصوى العناية: كشف التعارض بالحالة
     * الصغيرة (الأشد)، فلا يتغير السلوك عن السابق إلا حيث الوجهة تسمح فعلاً.
     */
    private fun probeCaseSensitive(dir: Path): Boolean {
        val base = ".fm-case-probe-" + UUID.randomUUID().toString().take(8)
        val a = dir.resolve(base)
        val b = dir.resolve(base.uppercase(java.util.Locale.ROOT))
        return try {
            Files.createFile(a)
            try {
                Files.createFile(b)
                true
            } catch (e: FileAlreadyExistsException) {
                false
            }
        } catch (e: IOException) {
            false
        } finally {
            runCatching { Files.deleteIfExists(a) }
            runCatching { Files.deleteIfExists(b) }
        }
    }

    fun extract(
        archive: Path,
        destinationDir: Path,
        onProgress: (entries: Int, bytes: Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
        filter: EntryFilter = EntryFilter.ALL,
        /** اسم بديل للنتيجة النهائية («الاسم (1)») عند وجود الاسم الأصلي في الوجهة. */
        finalNameOverride: String? = null,
        /** [0.7.9] يُستدعى بعد اكتمال كتابة كل ملف باسم مدخله — يعلّم عناصر سجل
         *  الاسترداد DONE واحدًا واحداً (ArchiveOpEngine) فيعرف الاستئناف ما نُفذ. */
        onEntryWritten: (String) -> Unit = {},
        /** نقل/نسخ عناصر محددة من الأرشيف إلى مجلد: كل عنصر محدد يوضع مباشرة في [destinationDir]
         *  باسمه الأخير (بلا مجلد باسم الأرشيف ولا مسار داخلي)؛ التعارض يأخذ «الاسم (1)» ولا يُستبدل شيء. */
        placeSelectedDirectly: Collection<String>? = null
    ): ExtractReport {
        val kind = ArchiveFormat.detect(archive)
        val archiveName = archive.fileName.toString()
        val isSingleFile = kind == ArchiveFormat.Kind.GZ || kind == ArchiveFormat.Kind.XZ
        val naturalName = if (isSingleFile) ArchiveFormat.singleFileName(archiveName) else ArchiveFormat.folderName(archiveName)
        val direct = placeSelectedDirectly != null && !isSingleFile
        val finalName = finalNameOverride ?: naturalName
        if (!direct) NameRules.problem(finalName, strictFat = false)?.let { throw UnsafeEntryException("invalid output name ($it): $finalName") }
        val finalPath = if (direct) destinationDir else destinationDir.resolve(finalName)
        if (!direct && Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(finalPath.toString())

        Files.createDirectories(destinationDir)
        val tmp = destinationDir.resolve(".fm-extract-" + UUID.randomUUID().toString().take(8))
        Files.createDirectory(tmp)
        try {
            val root = tmp.toRealPath()
            val session = Session(root, probeCaseSensitive(tmp), onProgress, checkCancelled, filter, onEntryWritten)
            Files.newInputStream(archive).use { fileStream ->
                val counting = CountingInputStream(fileStream)
                session.counting = counting
                val raw = BufferedInputStream(counting, 64 * 1024)
                when (kind) {
                    // (مراجعة 0.8.6-#4) القراءة عبر الترويسات المحلية (مجرى): تتسامح مع
                    // أرشيفات تالف دليلها المركزي، لكن أسماءها قد تختلف عمّا يعرضه
                    // المفتش (دليل مركزي) في أرشيف مُصنَّع. كل اسم يُتحقق من صحته هنا
                    // مجدداً (SafeResolve) وكل استخراج بالاسم المحدد يفشل بإغلاق آمن
                    // عند عدم المطابقة (ui_err_open_entry) — فالاختلاف تشكيلي لا أمني.
                    ArchiveFormat.Kind.ZIP -> ZipArchiveInputStream(raw, charset.name(), true, true).use { zip ->
                        session.copyEntries(zip) { zip.nextEntry }
                    }
                    ArchiveFormat.Kind.TAR -> TarArchiveInputStream(raw, charset.name()).use { tar ->
                        session.copyEntries(tar) { tar.nextEntry }
                    }
                    ArchiveFormat.Kind.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(raw), charset.name()).use { tar ->
                        session.copyEntries(tar) { tar.nextEntry }
                    }
                    ArchiveFormat.Kind.TAR_XZ -> TarArchiveInputStream(
                        XZCompressorInputStream(raw, false, limits.xzMemoryLimitKb),
                        charset.name()
                    ).use { tar -> session.copyEntries(tar) { tar.nextEntry } }
                    ArchiveFormat.Kind.GZ -> GzipCompressorInputStream(raw).use { session.copySingle(it, finalName) }
                    ArchiveFormat.Kind.XZ -> XZCompressorInputStream(raw, false, limits.xzMemoryLimitKb).use { session.copySingle(it, finalName) }
                }
            }
            // بيانات كل ملف ومجلد تُدوَّم قبل النشر: إعادة التسمية الذرية وحدها
            // تحمي الاسم لا المحتوى بعد انقطاع الطاقة.
            AtomicIO.fsyncTree(tmp)
            if (direct) {
                placeDirectly(root, destinationDir, placeSelectedDirectly!!)
            } else if (isSingleFile) {
                AtomicIO.moveNoReplace(root.resolve(finalName), finalPath)
            } else if (filter === EntryFilter.ALL) {
                AtomicIO.moveNoReplace(unwrapRoot(tmp, naturalName), finalPath)
            } else {
                AtomicIO.moveNoReplace(tmp, finalPath)
            }
            return ExtractReport(finalPath, session.entries, session.total, session.skipped.toList(), session.written)
        } finally {
            runCatching { AtomicIO.deleteTree(tmp) }
        }
    }

    /** يضع كل عنصر محدد مباشرة في [dest] باسمه الأخير؛ الأب يُنقل قبل أبنائه فلا يُنقل الابن مرتين. */
    private fun placeDirectly(root: Path, dest: Path, selected: Collection<String>) {
        val names = selected.map { it.replace('\\', '/').trim('/') }
            .filter { it.isNotEmpty() }.distinct().sortedBy { n -> n.count { it == '/' } }
        for (sel in names) {
            val src = root.resolve(sel).normalize()
            if (!src.startsWith(root)) continue
            if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) continue
            val base = src.fileName.toString()
            val dot = base.lastIndexOf('.').takeIf { it > 0 && !Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS) }
            val stem = if (dot != null) base.substring(0, dot) else base
            val ext = if (dot != null) base.substring(dot) else ""
            var name = base
            var i = 1
            while (i <= 100_000 && Files.exists(dest.resolve(name), LinkOption.NOFOLLOW_LINKS)) { name = "$stem ($i)$ext"; i++ }
            AtomicIO.moveNoReplace(src, dest.resolve(name))
        }
    }

    /**
     * إن كان المحتوى المستخرج مجلدًا واحدًا جذرُه يحمل اسم الأرشيف نفسه فيُعاد هو
     * بدل tmp (بلا تدوير مزدوج)؛ وإلا فtmp نفسه هو الجذر.
     */
    private fun unwrapRoot(tmp: Path, finalName: String): Path {
        val children = Files.list(tmp).use { s -> s.iterator().asSequence().toList() }
        if (children.size != 1) return tmp
        val only = children[0]
        return if (Files.isDirectory(only) && only.fileName.toString().equals(finalName, ignoreCase = true)) only else tmp
    }

    /** حالة جلسة استخراج واحدة. */
    private inner class Session(
        val root: Path,
        val caseSensitive: Boolean,
        val onProgress: (Int, Long) -> Unit,
        val checkCancelled: () -> Unit,
        val filter: EntryFilter,
        /** [0.7.9] رد نداء اكتمال كتابة كل ملف (انظر extract). */
        val onEntryWritten: (String) -> Unit
    ) {
        lateinit var counting: CountingInputStream
        var entries = 0
        var written = 0
        var total = 0L
        val skipped = ArrayList<String>()
        private val seen = HashSet<String>()
        private val buffer = ByteArray(64 * 1024)

        fun <E : ArchiveEntry> copyEntries(stream: ArchiveInputStream<E>, next: () -> ArchiveEntry?) {
            while (true) {
                checkCancelled()
                val entry = next() ?: break
                if (++entries > limits.maxEntries) throw ArchiveLimitException("too many entries (> ${limits.maxEntries})")
                if (SafeResolve.isRootMarker(entry.name)) continue
                val selected = filter.matches(entry.name)
                if (isLinkOrSpecial(entry)) {
                    if (selected) skipped += entry.name
                    continue
                }
                // In partial extraction, unrelated entries are skipped before path
                // validation. A selected unsafe path still fails closed, while an
                // unsafe entry the user did not select cannot block safe selections.
                if (!selected) continue
                val target = SafeResolve.resolve(root, entry.name, limits.maxDepth)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                // كشف التعارض بالحالة الصغيرة على الأنظمة غير الحساسة للحالة فقط:
                // على ext4 فأشباه «README/readme» ملفان مشروعان تُستقبل معاً.
                val rel = root.relativize(target).toString()
                if (!seen.add(if (caseSensitive) rel else rel.lowercase())) {
                    throw UnsafeEntryException("duplicate entry: ${entry.name}")
                }
                if (!stream.canReadEntryData(entry)) throw IOException("unsupported entry encoding: ${entry.name}")
                target.parent?.let { Files.createDirectories(it) }
                writeFile(stream, target)
                written++
                onProgress(written, total)
                onEntryWritten(entry.name)
            }
        }

        fun copySingle(input: InputStream, name: String) {
            entries = 1
            writeFile(input, root.resolve(name))
            written = 1
            onProgress(written, total)
            onEntryWritten(name)
        }

        /**
         * الحدود تُحسب على البايتات **المكتوبة فعلاً** لا على الأحجام المعلنة في الأرشيف (قابلة للكذب).
         */
        private fun writeFile(input: InputStream, target: Path) {
            val startRaw = counting.count
            var written = 0L
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    total += n
                    if (total > limits.maxTotalBytes) throw ArchiveLimitException("total size limit exceeded")
                    val allowed = limits.ratioSlackBytes + limits.maxRatio * (counting.count - startRaw + 64 * 1024)
                    if (written > allowed) throw ArchiveLimitException("compression ratio limit exceeded: ${target.fileName}")
                    val allowedTotal = limits.ratioSlackBytes + limits.maxTotalRatio * (counting.count + 64 * 1024)
                    if (total > allowedTotal) throw ArchiveLimitException("cumulative compression ratio limit exceeded")
                }
            }
        }

        private fun isLinkOrSpecial(entry: ArchiveEntry): Boolean = when (entry) {
            is TarArchiveEntry -> entry.isSymbolicLink || entry.isLink ||
                entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO
            is ZipArchiveEntry -> entry.isUnixSymlink
            else -> false
        }
    }

    /** يعدّ البايتات الخام المقروءة من الملف (قبل فك الضغط) لحساب نسبة الضغط. */
    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) count += s
            return s
        }
    }
}
