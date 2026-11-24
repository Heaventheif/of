package org.openfilemanager.app

import java.nio.file.Paths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** طلب كما قُرئ من المخزن: نوع العملية، مسارات المصادر، والوجهة إن وُجدت.
 *  [extra] اسم هدف الضغط أو اسم ترميز الاستخراج؛ [selected] عناصر الاستخراج
 *  المحددة (null = الكل). */
data class StoredOpRequest(
    val kind: String,
    val paths: List<String>,
    val target: String?,
    val extra: String? = null,
    val selected: List<String>? = null
)

/** قائمة عارض/مشغل كما قُرئت من المخزن: النوع، العناصر، وموضع المفتوح. */
data class StoredPlaylist(val kind: String, val items: List<String>, val index: Int)

/**
 * طلبات العمليات على القرص بدل Intent extras: يحدّ Binder حجم معاملات
 * startForegroundService بنحو ميغابايت واحد، وتحديد كل ملفات مجلد كبير كان
 * يرمي TransactionTooLargeException يُختزل إلى «false» فتضيع العملية بصمت.
 * الملف يُكتب في التخزين الخاص للتطبيق وتحمل النية رمزه فقط.
 *
 * الاستئناف لا يمرّ من هنا: رمزه نص واحد قصير يبقى في extras.
 */
object PendingRequestStore {
    const val KIND_COPY = "COPY"
    const val KIND_MOVE = "MOVE"
    const val KIND_DELETE = "DELETE"

    /** قائمة مسارات صور للعارض (6). */
    const val KIND_VIEWER_IMAGES = "VIEWER_IMAGES"

    /** قائمة URIs صوت للمشغل (6). */
    const val KIND_PLAYER_URIS = "PLAYER_URIS"

    /** ضغط إلى ZIP عبر خدمة العمليات (5). */
    const val KIND_COMPRESS = "COMPRESS"

    /** استخراج أرشيف عبر خدمة العمليات (5). */
    const val KIND_EXTRACT = "EXTRACT"

    private const val DIR_NAME = "pending-ops"

    /** [0.7.9] علامات «الطلب قيد التنفيذ» — ملف لكل opId. */
    private const val ACTIVE_DIR = "active-ops"

    /** [0.7.9] نسخ احتياطية للطلبات باسم opId (للاسترداد بعد موت العملية). */
    private const val PENDING_BACKUP_DIR = "pending-backup"
    private const val MAX_STORED_ITEMS = 100_000
    private const val MAX_STORED_FILE_BYTES = 64L * 1024 * 1024
    private val SAFE_TOKEN = Regex("^[A-Za-z0-9-]{1,64}$")

    private fun validToken(value: String): Boolean = SAFE_TOKEN.matches(value)

    /** DataOutput.writeUTF uses modified UTF-8 and a 16-bit length prefix. */
    private fun modifiedUtfBytes(value: String): Long {
        var bytes = 0L
        for (ch in value) {
            bytes += when (ch.code) {
                in 0x0001..0x007F -> 1L
                in 0x0000..0x07FF -> 2L
                else -> 3L
            }
            if (bytes > 65_535L) throw IllegalArgumentException("stored string exceeds writeUTF limit")
        }
        return bytes + 2L
    }

    /** Refuse requests larger than the reader's hard limit before creating a file. */
    private fun requirePayloadWithinLimit(strings: Iterable<String>, fixedBytes: Long) {
        var total = fixedBytes
        for (value in strings) {
            total += modifiedUtfBytes(value)
            require(total <= MAX_STORED_FILE_BYTES) { "stored request exceeds size limit" }
        }
    }

    /** Write private state to a sibling file, sync its bytes, then publish it. */
    private fun atomicPrivateWrite(destination: File, write: (DataOutputStream) -> Unit) {
        val parent = destination.parentFile ?: throw java.io.IOException("missing private store parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw java.io.IOException("cannot create private store directory")
        val temp = File(parent, ".tmp-${destination.name}-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { raw ->
                val out = DataOutputStream(BufferedOutputStream(raw))
                write(out)
                out.flush()
                raw.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    temp.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            runCatching {
                java.nio.channels.FileChannel.open(parent.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }

    /** يكتب الطلب في تخزين التطبيق ويعيد رمزه الفريد. [extra] و[selected]
     *  لطلبي الضغط/الاستخراج (5) — انظر [StoredOpRequest]. */
    fun write(
        root: File,
        kind: String,
        paths: List<String>,
        target: String?,
        extra: String? = null,
        selected: List<String>? = null
    ): String {
        require(paths.size in 1..MAX_STORED_ITEMS) { "invalid stored request item count" }
        require(selected == null || selected.size <= MAX_STORED_ITEMS) { "too many selected archive entries" }
        requirePayloadWithinLimit(
            sequenceOf(kind).plus(paths.asSequence()).plus(sequenceOf(target ?: "", extra ?: ""))
                .plus(selected.orEmpty().asSequence()).asIterable(),
            fixedBytes = 8L
        )
        val dir = File(root, DIR_NAME)
        val token = UUID.randomUUID().toString()
        atomicPrivateWrite(File(dir, token)) { out ->
            out.writeUTF(kind)
            out.writeInt(paths.size)
            for (path in paths) out.writeUTF(path)
            out.writeUTF(target ?: "")
            out.writeUTF(extra ?: "")
            if (selected == null) {
                out.writeInt(-1)
            } else {
                out.writeInt(selected.size)
                for (name in selected) out.writeUTF(name)
            }
        }
        return token
    }

    /**
     * قائمة عرض للعارض/المشغل (6): قائمة مسارات/URI كاملة تُكتب هنا بدل
     * Intent extras — آلاف المداخل تتجاوز حد Binder (~1MB) فترمي
     * TransactionTooLargeException. [index] موضع العنصر المفتوح ضمن القائمة.
     */
    fun writeViewerPlaylist(root: File, kind: String, items: List<String>, index: Int): String {
        require(kind == KIND_VIEWER_IMAGES || kind == KIND_PLAYER_URIS) { "unsupported playlist kind" }
        require(items.isNotEmpty() && items.size <= MAX_STORED_ITEMS && index in items.indices) { "invalid playlist bounds" }
        requirePayloadWithinLimit(sequenceOf(kind).plus(items.asSequence()).asIterable(), fixedBytes = 8L)
        val dir = File(root, DIR_NAME)
        val token = UUID.randomUUID().toString()
        atomicPrivateWrite(File(dir, token)) { out ->
            out.writeUTF(kind)
            out.writeInt(items.size)
            for (item in items) out.writeUTF(item)
            out.writeInt(index)
        }
        return token
    }

    /** يقرأ قائمة العارض/المشغل ويستهلك ملفها؛ null إن غاب أو تلف. */
    fun consumeViewerPlaylist(root: File, token: String): StoredPlaylist? {
        val playlist = peekViewerPlaylist(root, token)
        deleteViewerPlaylist(root, token)
        return playlist
    }

    /** يقرأ القائمة دون حذف ملفها: العارض يحتاجها بعد إعادة إنشاء النشاط (تدوير/لغة). */
    fun peekViewerPlaylist(root: File, token: String): StoredPlaylist? {
        if (!validToken(token)) return null
        val file = File(File(root, DIR_NAME), token)
        if (!file.isFile || file.length() > MAX_STORED_FILE_BYTES) return null
        val playlist = runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val kind = input.readUTF()
                if (kind != KIND_VIEWER_IMAGES && kind != KIND_PLAYER_URIS) return null
                val count = input.readInt()
                if (count !in 1..MAX_STORED_ITEMS) return null
                val items = ArrayList<String>(count)
                repeat(count) { items += input.readUTF() }
                val index = input.readInt()
                if (index !in items.indices || input.read() != -1) return null
                StoredPlaylist(kind, items, index)
            }
        }.getOrNull()
        return playlist
    }

    /** يحذف ملف قائمة عرض (عند انتهاء النشاط فعلاً لا عند إعادة إنشائه). */
    fun deleteViewerPlaylist(root: File, token: String) {
        if (!validToken(token)) return
        runCatching { File(File(root, DIR_NAME), token).delete() }
    }

    /**
     * يقرأ الطلب ويستهلك ملفه (قراءة واحدة لكل رمز؛ الخدمة START_NOT_STICKY فلا
     * يعاد تسليم النية، وبقاؤه بعد الاستهلاك يعني ملفات تنساب بلا تنظيف).
     * null إن غاب الملف أو تلف محتواه.
     *
     * (F-07) مهجور في مسار خدمة العمليات لصالح [holdAndRead] التسليم الذري؛
     * يبقى للاستدعاءات الأخرى المحتملة وللاختبارات.
     */
    fun consume(root: File, token: String): StoredOpRequest? {
        if (!validToken(token)) return null
        val file = File(File(root, DIR_NAME), token)
        if (!file.isFile) return null
        if (file.length() > MAX_STORED_FILE_BYTES) {
            file.delete()
            return null
        }
        val request = runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val kind = input.readUTF()
                val count = input.readInt()
                if (count !in 1..MAX_STORED_ITEMS) return null
                val paths = ArrayList<String>(count)
                repeat(count) { paths += input.readUTF() }
                val target = input.readUTF().ifEmpty { null }
                val extra = input.readUTF().ifEmpty { null }
                val selectedCount = input.readInt()
                val selected = when {
                    selectedCount == -1 -> null
                    selectedCount in 0..MAX_STORED_ITEMS -> ArrayList<String>(selectedCount).apply {
                        repeat(selectedCount) { add(input.readUTF()) }
                    }
                    else -> return null
                }
                if (input.read() != -1) return null
                StoredOpRequest(kind, paths, target, extra, selected)
            }
        }.getOrNull()?.let(::normalizedOrNull)
        file.delete()
        return request
    }

    /** قراءة الطلب من ملف واحد دون حذفه (جسم قراءة مشترك بين consume وpeek وholdAndRead). */
    private fun readStored(file: File): StoredOpRequest? {
        if (!file.isFile || file.length() > MAX_STORED_FILE_BYTES) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val kind = input.readUTF()
                val count = input.readInt()
                if (count !in 1..MAX_STORED_ITEMS) return null
                val paths = ArrayList<String>(count)
                repeat(count) { paths += input.readUTF() }
                val target = input.readUTF().ifEmpty { null }
                val extra = input.readUTF().ifEmpty { null }
                val selectedCount = input.readInt()
                val selected = when {
                    selectedCount == -1 -> null
                    selectedCount in 0..MAX_STORED_ITEMS -> ArrayList<String>(selectedCount).apply {
                        repeat(selectedCount) { add(input.readUTF()) }
                    }
                    else -> return null
                }
                if (input.read() != -1) return null
                StoredOpRequest(kind, paths, target, extra, selected)
            }
        }.getOrNull()?.let(::normalizedOrNull)
    }

    /**
     * (F-07) تسليم ذري للطلب من ملف الرمز إلى النسخة الاحتياطية المربوطة بـopId:
     * كان decode يحذف ملف الطلب أولاً ثم تكتب النسخة الاحتياطية وعلامة التنفيذ
     * داخل runOp — فتوجد نافذة موت عملية يضيع فيها الطلب كلياً (لا ملف أصلي
     * ولا نسخة ولا علامة). الآن: إعادة تسمية واحدة داخل التخزين الخاص تنقل
     * الملف إلى pending-backup/<opId> قبل أي قراءة، فالطلب موجود في مكان واحد
     * دائم من هذه اللحظة، ولا يُحذف إلا بعد خاتمة العملية (removePending في
     * finally). إن كانت النسخة موجودة مسبقاً (انقطاع بين النقل والقراءة) فتُقرأ
     * هي — الاستدعاء متماثل (idempotent).
     * يعيد null إن غاب الملفان (رمز ونسخة) أو تلفا.
     */
    fun holdAndRead(root: File, token: String, opId: String): StoredOpRequest? {
        if (!validToken(token) || !validToken(opId)) return null
        val src = File(File(root, DIR_NAME), token)
        val dst = File(File(root, PENDING_BACKUP_DIR), opId)
        if (src.isFile) {
            if (src.length() > MAX_STORED_FILE_BYTES) {
                src.delete()
                return null
            }
            val dstDir = dst.parentFile ?: return null
            if (!dstDir.isDirectory && !dstDir.mkdirs()) return null
            val moved = runCatching {
                try {
                    java.nio.file.Files.move(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(src.toPath(), dst.toPath())
                }
                true
            }.getOrDefault(false)
            // فشل النقل مع بقاء المصدر: يُقرأ المصدر نفسه (أسوأ الحالات = سلوك
            // consume القديم دون حذف، فينظفه purgeOlderThan بالعمر لاحقاً).
            if (!moved && !dst.isFile) return readStored(src)
        }
        // بعد النقل (أو إن كانت النسخة موجودة مسبقاً بعد انقطاع بين النقل والقراءة).
        return readStored(dst)
    }

    /**
     * يطبّع مسارات الطلب ويرفض غير المطلق منها (ملف الطلب داخل filesDir الخاص، فالخطر
     * تلف الملف أو تلاعب بجهاز مُجذَّر): مسار نسبي كان يُحلّ على مجلد عمل العملية.
     * null = الطلب مرفوض كله.
     */
    private fun normalizedOrNull(request: StoredOpRequest): StoredOpRequest? {
        if (request.kind !in setOf(KIND_COPY, KIND_MOVE, KIND_DELETE, KIND_COMPRESS, KIND_EXTRACT)) return null
        if (request.paths.isEmpty() || request.paths.size > MAX_STORED_ITEMS) return null
        if (request.selected != null && request.selected.size > MAX_STORED_ITEMS) return null
        if (request.kind != KIND_DELETE && request.target.isNullOrBlank()) return null
        if (request.kind == KIND_DELETE && request.target != null) return null
        if (request.kind == KIND_COMPRESS && request.extra.isNullOrBlank()) return null
        fun norm(raw: String): String? = runCatching {
            val path = java.nio.file.Paths.get(raw)
            if (!path.isAbsolute) null else path.normalize().toString()
        }.getOrNull()
        val paths = request.paths.map { norm(it) ?: return null }
        val target = request.target?.let { norm(it) ?: return null }
        return request.copy(paths = paths, target = target)
    }

    /** إزالة ملف طلب لم يبدأ (فشل إرسال النية بعد كتابته). */
    fun remove(root: File, token: String) {
        if (!validToken(token)) return
        runCatching { File(File(root, DIR_NAME), token).delete() }
    }

    /**
     * تنظيف المعلّق القديم: العملية ماتت بين كتابة الملف وتسليم نيته، والنظام لا
     * يعيد التسليم (START_NOT_STICKY) فهرمت ملفاته. التنظيف بالعمر لا فور
     * الإنشاء، كي لا نحذف ملف نية في طريقها إلينا الآن بعد إعادة تشغيل العملية.
     */
    fun purgeOlderThan(root: File, maxAgeMs: Long) {
        val dir = File(root, DIR_NAME)
        // [0.7.9] newDirectoryStream بدل File.listFiles (بوابة الجودة: بنداء
        // نظام واحد، والمجلد قد لا يوجد فنلتقط NoSuchFileException بهدوء).
        runCatching {
            java.nio.file.Files.newDirectoryStream(dir.toPath()).use { stream ->
                val deadline = System.currentTimeMillis() - maxAgeMs
                for (path in stream) {
                    val file = path.toFile()
                    if (file.isFile && file.lastModified() < deadline) runCatching { file.delete() }
                }
            }
        }
    }

    /**
     * [0.7.9] علامة «الطلب قيد التنفيذ»: ملف pending-ops يُستهلك لحظة decode
     * فور وصول النية، لكن الطلب يبقى معلّقاً حتى تنتهي العملية — موت العملية
     * بين الاستهلاك والإنهاء كان يفقد الطلب كلياً (لا نية تعاد ولا ملف يُقرأ).
     * العلامة تعيش في مجلد مستقل حتى لا تبتلعها دورة consume/remove نفسها.
     * [opId] معرّف العملية الذي سافر مع النية (المفتاح الذي يربط السجل بها).
     */
    fun markActive(root: File, opId: String) {
        require(validToken(opId)) { "invalid operation id" }
        val dir = File(root, ACTIVE_DIR)
        atomicPrivateWrite(File(dir, opId)) { out -> out.writeUTF(System.currentTimeMillis().toString()) }
    }

    /**
     * [0.7.9] يقرأ الطلبات المعلّقة غير المنتهية عند بدء الخدمة ويحذف ملفاتها
     * (تعاد كتابة العلامة عند بدء التنفيذ الجديد). يُصفّي:
     * - الهرم (أقدم من [staleMs]): النظام لا يعيد التسليم أصلاً فليس له معنى.
     * - ما له سجل في [journal] بحالة FINISHED/FAILED/CANCELLED: العملية انتهت
     *   فعلاً قبل موت العملية وبقي ملف العلامة فقط (انحراف نادر: انقطاع بين
     *   إنهاء السجل ومحو العلامة).
     * - ما له سجل RUNNING/INTERRUPTED: العلامة تُمحى والقرار (استئناف/إلغاء)
     *   لحوار الاسترداد (F-01) الذي يعمل من سجل العمليات مباشرة — النسخة
     *   الاحتياطية لم تعد ضرورة لهذا المسار فتُمحى أيضاً (لا يُعاد تشغيل الطلب
     *   من أولها صامتاً فتهدم التقدم الجزئي المسجّل).
     * - (F-07) نسخ احتياطية يتيمة بلا علامة: بعد holdAndRead يمكن أن يموت
     *   الملف العملية بين النقل الذري وعلامة markActive — فالمسح يشمل مجلد
     *   النسخ الاحتياطية نفسه بقواعد الحالة ذاتها: هرم أو عملية منتهية → حذف؛
     *   RUNNING/INTERRUPTED → يُترك لحوار الاسترداد؛ بلا سجل → يُسترد ويُعاد
     *   إدخاله (الطلب لم يبدأ قط).
     * يعيد أزواج (opId، الطلب المخزّن) للطلبات التي لا يزال تنفيذها مطلوباً.
     * آمن للاستدعاء المتكرر.
     */
    fun recoverActive(
        root: File,
        journal: org.openfilemanager.core.OpJournal,
        staleMs: Long
    ): List<Pair<String, StoredOpRequest>> {
        val activeDir = File(root, ACTIVE_DIR)
        val deadline = System.currentTimeMillis() - staleMs
        val recovered = ArrayList<Pair<String, StoredOpRequest>>()
        // [0.7.9] newDirectoryStream بدل listFiles (بوابة الجودة نفسها).
        // (F-07) مجموعة المرشحين = العلامات + النسخ الاحتياطية اليتيمة.
        val markerNames = runCatching {
            java.nio.file.Files.newDirectoryStream(activeDir.toPath()).use { stream ->
                stream.map { it.fileName.toString() }.toSet()
            }
        }.getOrDefault(emptySet())
        val backupNames = runCatching {
            java.nio.file.Files.newDirectoryStream(File(root, PENDING_BACKUP_DIR).toPath()).use { stream ->
                stream.map { it.fileName.toString() }.toSet()
            }
        }.getOrDefault(emptySet())
        val candidates = LinkedHashSet<String>().apply { addAll(markerNames); addAll(backupNames) }
        for (name in candidates) {
            val opId = name
            if (!validToken(opId)) {
                // اسم غير صالح في أي من المجلدين: ملف يتيم يُمحى.
                runCatching { File(activeDir, name).delete() }
                runCatching { File(File(root, PENDING_BACKUP_DIR), name).delete() }
                continue
            }
            val marker = File(activeDir, opId)
            val backup = File(File(root, PENDING_BACKUP_DIR), opId)
            val newest = maxOf(
                if (marker.isFile) marker.lastModified() else Long.MIN_VALUE,
                if (backup.isFile) backup.lastModified() else Long.MIN_VALUE
            )
            if (newest == Long.MIN_VALUE) continue
            if (newest < deadline) {
                runCatching { marker.delete() }
                removePending(root, opId)
                continue
            }
            val state = runCatching {
                journal.op(opId)?.state
            }.getOrNull()
            when (state) {
                org.openfilemanager.core.OpState.FINISHED,
                org.openfilemanager.core.OpState.FAILED,
                org.openfilemanager.core.OpState.CANCELLED -> {
                    // العملية انتهت فعلاً — كلا الأثرين يتيمان.
                    runCatching { marker.delete() }
                    removePending(root, opId)
                }
                org.openfilemanager.core.OpState.RUNNING,
                org.openfilemanager.core.OpState.INTERRUPTED -> {
                    // القرار (استئناف/إلغاء) لحوار الاسترداد من السجل لا من النسخة،
                    // فلا يبقى إلا حذف العلامة والنسخة الاحتياطية (F-01/F-07).
                    runCatching { marker.delete() }
                    removePending(root, opId)
                }
                null -> {
                    // لا سجل: الطلب لم يبدأ تنفيذه قط — يُسترد من النسخة الاحتياطية
                    // (التي كتبها holdAndRead ذرياً) ويُعاد إدخاله كأنه وصل الآن؛
                    // بلا نسخة فالأثر كله يتيم يُمحى.
                    val pending = peek(root, opId)
                    if (pending == null) {
                        runCatching { marker.delete() }
                        removePending(root, opId)
                    } else {
                        recovered += opId to pending
                    }
                }
            }
        }
        return recovered
    }

    /** [0.7.9] قراءة طلب معلّق دون استهلاك ملفه (لاسترداده مرة أخرى). */
    private fun peek(root: File, opId: String): StoredOpRequest? {
        if (!validToken(opId)) return null
        return readStored(File(File(root, PENDING_BACKUP_DIR), opId))
    }

    /**
     * [0.7.9] محو علامة «قيد التنفيذ» عند اكتمال العملية (نجاحاً أو فشلاً):
     * البقاء بعد الانتهاء كان يعيد الطلب عند أول إقلاع قادم للخدمة.
     */
    fun clearActive(root: File, opId: String) {
        if (!validToken(opId)) return
        runCatching { File(File(root, ACTIVE_DIR), opId).delete() }
    }

    /** [0.7.9] كتابة نسخة احتياطية من الطلب باسم opId قبل تسليم النية (للاسترداد). */
    fun writePending(root: File, opId: String, kind: String, paths: List<String>, target: String?, extra: String? = null, selected: List<String>? = null) {
        require(validToken(opId)) { "invalid operation id" }
        require(kind in setOf(KIND_COPY, KIND_MOVE, KIND_DELETE, KIND_COMPRESS, KIND_EXTRACT)) { "invalid pending request kind" }
        require(paths.size in 1..MAX_STORED_ITEMS && (selected == null || selected.size <= MAX_STORED_ITEMS))
        requirePayloadWithinLimit(
            sequenceOf(kind).plus(paths.asSequence()).plus(sequenceOf(target ?: "", extra ?: ""))
                .plus(selected.orEmpty().asSequence()).asIterable(),
            fixedBytes = 8L
        )
        val dir = File(root, PENDING_BACKUP_DIR)
        atomicPrivateWrite(File(dir, opId)) { out ->
            out.writeUTF(kind)
            out.writeInt(paths.size)
            for (path in paths) out.writeUTF(path)
            out.writeUTF(target ?: "")
            out.writeUTF(extra ?: "")
            if (selected == null) {
                out.writeInt(-1)
            } else {
                out.writeInt(selected.size)
                for (name in selected) out.writeUTF(name)
            }
        }
    }

    /** [0.7.9] محو النسخة الاحتياطية عند اكتمال العملية (مع العلامة). */
    fun removePending(root: File, opId: String) {
        if (!validToken(opId)) return
        runCatching { File(File(root, PENDING_BACKUP_DIR), opId).delete() }
    }
}
