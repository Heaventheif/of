package org.openfilemanager.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** أُلغيت العملية بطلب المستخدم. النسخ/النقل لا ينشران ملفاً جزئياً؛ أما الحذف فقد يكون جزئياً ويُستأنف من السجل. */
class OperationCancelledException : RuntimeException("operation cancelled")

/** نقل/نسخ عنصر إلى داخل نفسه (قارن toRealPath ثم st_dev/st_ino). */
class TargetInsideSourceException(message: String) : IOException(message)

/** سياسة التعارض الافتراضية للعملية (الإعداد 7.5). ASK تعني حواراً للمستخدم. */
enum class ConflictPolicy { ASK, REPLACE, KEEP_BOTH, SKIP }

/** قرار المستخدم لحالة تعارض واحدة. */
data class ConflictDecision(val policy: ConflictPolicy, val applyToAll: Boolean)

/** تعارض يُعرض على المستخدم عند السياسة ASK. */
data class ConflictRequest(
    val source: Path,
    val target: Path,
    val isDirectory: Boolean
)

data class FileOpProgress(
    val itemIndex: Int,
    val itemCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val current: String
)

data class FileOpStats(val ok: Int, val failed: Int, val skipped: Int) {
    val total: Int get() = ok + failed + skipped
}

/**
 * محرك الدفعات: نسخ ونقل وحذف فوق [AtomicIO] مع سجل قابل للاسترداد.
 *
 * - النسخ: ملف مؤقت في مجلد الوجهة ثم نقل ذري؛ لا ملف نصف مكتمل باسم نهائي أبداً.
 * - النقل داخل الوحدة: إعادة تسمية ذرية (الشجرة كلها بنداء واحد).
 * - النقل بين وحدتين: نسخ بهاش ← إعادة قراءة الوجهة ومقارنة الهاش ← حذف المصدر عند التطابق فقط.
 * - التعارض: تعليق العملية على قرار المستخدم (استبدال/إبقاء الاثنين/تخطي + تطبيق على الكل).
 * - الإلغاء فوري (فحص كل دفعة)، والعملية المقطوعة تُستأنف من السجل عنصراً عنصراً.
 *
 * [sameVolume] تُحقن من طبقة أندرويد عبر `Os.stat().st_dev`؛ القياس الافتراضي بمقارنة
 * نظام الملفات يكفي للاختبارات (Jimfs) لكنه لا يفرّق وحدات أندرويد، فالحقن إلزامي هناك.
 */
class FileOpEngine(
    private val journal: OpJournal,
    private val sameVolume: (Path, Path) -> Boolean = { a, b -> a.fileSystem == b.fileSystem },
    private val resolveConflict: suspend (ConflictRequest) -> ConflictDecision =
        { ConflictDecision(ConflictPolicy.SKIP, true) },
    private val onProgress: (FileOpProgress) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
    private val dropPageCache: (Path) -> Unit = {},
    /**
     * (F-11) سقف عناصر التعداد القابل للضبط: تجاوزه يعني شجرة كبيرة جداً على
     * الذاكرة — تُفشل العملية برسالة واضحة قبل استهلاك الذاكرة لا بعده.
     */
    private val maxEnumeratedItems: Int = DEFAULT_MAX_ENUMERATED_ITEMS,
    /**
     * يفك مسارات السجل عند التنفيذ والاستئناف. الإنتاج يبقي الافتراضي (نظام ملفات الجهاز)،
     * والاختبارات تحقن Jimfs؛ فالمسار النصي وحده لا يكفي لتحديد نظام الملفات.
     */
    private val parsePath: (String) -> Path = { Paths.get(it) }
) {
    private fun checkCancelled() {
        if (isCancelled()) throw OperationCancelledException()
    }

    suspend fun copy(sources: List<Path>, targetDir: Path, policy: ConflictPolicy, opId: String = newOpId()): FileOpStats =
        batch(OpKind.COPY, sources, targetDir, policy, opId)

    suspend fun move(sources: List<Path>, targetDir: Path, policy: ConflictPolicy, opId: String = newOpId()): FileOpStats =
        batch(OpKind.MOVE, sources, targetDir, policy, opId)

    suspend fun delete(sources: List<Path>, opId: String = newOpId()): FileOpStats =
        batch(OpKind.DELETE, sources, sources.firstOrNull()?.parent ?: parsePath("."), ConflictPolicy.SKIP, opId)

    /**
     * استئناف عملية انقطعت (موت العملية أو مهلة الخدمة): يعيد العناصر غير المنجزة فقط.
     * سجل منتهٍ فعلاً (FINISHED/FAILED/CANCELLED) يُرفض لا يُعاد تنفيذه — إعادة
     * الاستئناف بعد نافذة سباق كانت تعيد عناصر FAILED وتكتب سطر إنهاء ثانياً
     * ونتيجة مكررة للمستخدم.
     */
    suspend fun resume(opId: String, policy: ConflictPolicy): FileOpStats {
        val record = journal.op(opId) ?: throw IOException("لا توجد عملية بهذا المعرف: $opId")
        if (record.state != OpState.RUNNING && record.state != OpState.INTERRUPTED) {
            throw IOException("العملية $opId منتهية أصلاً (${record.state.name.lowercase()}) فلا تُستأنف")
        }
        val items = journal.items(opId)
        if (items.isEmpty()) throw IOException("العملية فارغة: $opId")
        if (record.kind == OpKind.RENAME) throw IOException("التسمية تُستردّ من محرّكها الخاص")
        return try {
            run(record, items, policy)
        } catch (e: OperationCancelledException) {
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, 0, "أُلغيت")
            throw e
        } catch (e: Exception) {
            journal.finishOp(opId, OpState.FAILED, 0, 0, 0, e.message ?: "فشلت الاستئناف")
            throw e
        }
    }

    private suspend fun batch(
        kind: OpKind,
        sources: List<Path>,
        targetDir: Path,
        policy: ConflictPolicy,
        opId: String
    ): FileOpStats {
        if (sources.isEmpty()) return FileOpStats(0, 0, 0)
        try {
            if (kind != OpKind.DELETE) {
                // افحص المسار قبل إنشائه: createDirectories أولاً كان يترك
                // مجلداً فارغاً داخل المصدر حتى عندما نرفض النسخ/النقل إلى
                // داخل الشجرة نفسها. resolveRealPathAllowMissing يعالج أيضاً
                // وجهة غير موجودة تحت أب موجود أو رابط رمزي موجود.
                ensureTargetOutsideSources(targetDir, sources)
                Files.createDirectories(targetDir)
                // تحقق ثانية بعد الإنشاء للتقاط تبدّل المسار أو مكوّن رابط
                // رمزي خلال نافذة التهيئة.
                ensureTargetOutsideSources(targetDir, sources)
            }
            val items = enumerate(opId, kind, sources, targetDir, lazySizes = kind == OpKind.MOVE)
            val record = OpRecord(
                id = opId, kind = kind, state = OpState.RUNNING,
                sources = sources.map { it.toString() },
                targetDir = if (kind == OpKind.DELETE) "" else targetDir.toString(),
                createdAt = System.currentTimeMillis(),
                totalBytes = items.sumOf { it.size }
            )
            journal.startOp(record, items)
            return run(record, items, policy)
        } catch (e: OperationCancelledException) {
            journal.finishOp(opId, OpState.CANCELLED, 0, 0, 0, "أُلغيت")
            throw e
        } catch (e: Exception) {
            journal.finishOp(opId, OpState.FAILED, 0, 0, 0, e.message ?: "فشلت العملية")
            throw e
        }
    }

    /** يرفض الوجهة داخل أي مصدر حتى لو كانت بعض مكونات الوجهة لم تُنشأ بعد. */
    private fun ensureTargetOutsideSources(targetDir: Path, sources: List<Path>) {
        val realTarget = resolveRealPathAllowMissing(targetDir)
        for (source in sources) {
            val realSource = tryOrNull { source.toRealPath() } ?: continue
            if (realTarget == realSource || realTarget.startsWith(realSource)) {
                throw TargetInsideSourceException("الوجهة داخل المصدر نفسه: $source")
            }
        }
    }

    /** يحل الجزء الموجود فعلياً عبر toRealPath ثم يعيد إلحاق اللاحقة غير المنشأة. */
    private fun resolveRealPathAllowMissing(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing = absolute
        val missing = ArrayList<Path>()
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            val name = existing.fileName ?: break
            missing.add(name)
            existing = existing.parent ?: break
        }
        var resolved = existing.toRealPath()
        for (segment in missing.asReversed()) resolved = resolved.resolve(segment)
        return resolved.normalize()
    }

    // ---------- التعداد ----------

    private fun enumerate(opId: String, kind: OpKind, sources: List<Path>, targetDir: Path, lazySizes: Boolean): List<OpItemRecord> {
        val items = ArrayList<OpItemRecord>()
        var index = 0
        fun checkItemsCap() {
            // (F-11) سقف التعداد: فشل صريح برسالة واضحة بدل OOM صامت على أشجار ضخمة.
            if (items.size > maxEnumeratedItems) {
                throw IOException("enumeration exceeded $maxEnumeratedItems items; operation refused")
            }
        }
        checkItemsCap()
        sources.forEachIndexed { group, source ->
            val exists = Files.exists(source, LinkOption.NOFOLLOW_LINKS)
            val isDir = exists && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
            when {
                // Root symlinks are unlike nested symlinks: the latter are
                // already skipped by enumerateTree. A root link passed to copy or
                // cross-volume move would otherwise be opened by FileChannel and
                // copy the link target's contents. Same-volume move is a safe
                // rename of the link itself; delete also removes the link itself.
                exists && Files.isSymbolicLink(source) && kind != OpKind.DELETE &&
                    !(kind == OpKind.MOVE && sameVolume(source, targetDir)) -> {
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.FILE, state = ItemState.SKIPPED,
                        src = source.toString(), dst = targetDir.resolve(source.fileName).toString(),
                        extra = source.fileName.toString(), error = "رابط رمزي"
                    )
                    checkItemsCap()
                }

                kind == OpKind.DELETE -> {
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.TREE_DELETE, state = ItemState.PENDING,
                        src = source.toString(), dst = ""
                    )
                    checkItemsCap()
                }

                kind == OpKind.MOVE && sameVolume(source, targetDir) -> {
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.TREE_MOVE, state = ItemState.PENDING,
                        src = source.toString(), dst = targetDir.resolve(source.fileName).toString(),
                        // الحجم كسول: نقل داخل الوحدة إعادة تسمية O(1)؛ إحصاء الشجرة
                        // كلها لمجرد عدّاد بايتات كان يمشي المجلد كاملاً بلا حاجة.
                        // البايتات 0 = التقدم بالعدّ لا بالحجم (3.6).
                        size = if (lazySizes) 0 else treeSize(source)
                    )
                    checkItemsCap()
                }

                isDir -> {
                    var rootDst = targetDir.resolve(source.fileName)
                    if (rootDst == source) {
                        // نسخ مجلد داخل أبيه نفسه: مجلد نسخة باسم بديل «اسم (1)» —
                        // بدونه تصير الوجهة هي المصدر فتُضاعَف ملفاته داخله.
                        val base = source.fileName.toString()
                        var n = 1
                        var candidate = targetDir.resolve("$base ($n)")
                        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                            n++
                            candidate = targetDir.resolve("$base ($n)")
                        }
                        rootDst = candidate
                    }
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.DIR_CREATE, state = ItemState.PENDING,
                        src = source.toString(), dst = rootDst.toString()
                    )
                    checkItemsCap()
                    index = enumerateTree(opId, group, source, rootDst, items, index, ::checkItemsCap)
                }

                else ->
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.FILE, state = ItemState.PENDING,
                        src = source.toString(), dst = targetDir.resolve(source.fileName).toString(),
                        extra = source.fileName.toString(),
                        size = if (exists) fileSize(source) else 0
                    ).also { checkItemsCap() }
            }
        }
        return items
    }

    private fun enumerateTree(
        opId: String,
        group: Int,
        sourceRoot: Path,
        dstRoot: Path,
        items: ArrayList<OpItemRecord>,
        startIndex: Int,
        checkItemsCap: () -> Unit = {}
    ): Int {
        var index = startIndex
        Files.walkFileTree(sourceRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                checkItemsCap()
                if (dir != sourceRoot) {
                    val rel = sourceRoot.relativize(dir).toString()
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.DIR_CREATE, state = ItemState.PENDING,
                        src = dir.toString(), dst = dstRoot.resolve(rel).toString(), extra = rel
                    )
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                checkItemsCap()
                val rel = sourceRoot.relativize(file).toString()
                if (attrs.isSymbolicLink) {
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.FILE, state = ItemState.SKIPPED,
                        src = file.toString(), dst = dstRoot.resolve(rel).toString(),
                        extra = rel, error = "رابط رمزي"
                    )
                } else {
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.FILE, state = ItemState.PENDING,
                        src = file.toString(), dst = dstRoot.resolve(rel).toString(),
                        extra = rel, size = attrs.size()
                    )
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // One unreadable entry must not abort the whole enumeration: queue it as an
                // ordinary item so its own copy/move attempt fails (and is counted) alone.
                checkCancelled()
                checkItemsCap()
                val rel = sourceRoot.relativize(file).toString()
                items += OpItemRecord(
                    opId = opId, index = index++, group = group,
                    itemKind = OpItemKind.FILE, state = ItemState.PENDING,
                    src = file.toString(), dst = dstRoot.resolve(rel).toString(),
                    extra = rel, size = 0
                )
                return FileVisitResult.CONTINUE
            }
        })
        return index
    }

    private fun treeSize(root: Path): Long {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return fileSize(root)
        var total = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (!attrs.isSymbolicLink) total += attrs.size()
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    private fun fileSize(file: Path): Long =
        tryOrNull { Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size() } ?: 0

    // ---------- التنفيذ ----------

    private class Counters {
        var ok = 0
        var failed = 0
        var skipped = 0
        var bytesDone = 0L
    }

    private inner class RunContext(val record: OpRecord, val itemCount: Int, val bytesTotal: Long) {
        val c = Counters()
        var cachedDecision: ConflictDecision? = null
        val takenNames = HashMap<String, MutableSet<String>>()
        val emptiedSourceDirs = ArrayList<String>()

        fun progress(itemIndex: Int, current: String, bytesDoneOverride: Long? = null) {
            onProgress(FileOpProgress(itemIndex, itemCount, bytesDoneOverride ?: c.bytesDone, bytesTotal, current))
        }
    }

    // ---------- مساعدات تحديث السجل ----------

    private fun markRunning(ctx: RunContext, item: OpItemRecord) {
        journal.updateItem(ctx.record.id, item.index, ItemState.RUNNING)
    }

    private fun skipItem(ctx: RunContext, item: OpItemRecord, reason: String) {
        journal.updateItem(ctx.record.id, item.index, ItemState.SKIPPED, error = reason)
        ctx.c.skipped++
    }

    private fun failItem(ctx: RunContext, item: OpItemRecord, error: String) {
        journal.updateItem(ctx.record.id, item.index, ItemState.FAILED, error = error)
        ctx.c.failed++
    }

    private suspend fun run(record: OpRecord, items: List<OpItemRecord>, defaultPolicy: ConflictPolicy): FileOpStats {
        val ctx = RunContext(record, items.size, items.sumOf { it.size })

        items.forEach { item ->
            when (item.state) {
                ItemState.DONE -> { ctx.c.ok++; ctx.c.bytesDone += item.size }
                ItemState.SKIPPED -> ctx.c.skipped++
                else -> {}
            }
        }

        // تجميع بالقيمة لا بالتجاور: عناصر الدمج المسجلة في السجل (فهارس أعلى
        // من نطاق التعداد) تأتي في نهاية قائمة العناصر بعد مجموعات لاحقة،
        // ومعنى group ثابت: كل مصدر مستوى أعلى ومحتواه مجموعة واحدة.
        val groups = LinkedHashMap<Int, MutableList<OpItemRecord>>()
        items.forEach { groups.getOrPut(it.group) { ArrayList() }.add(it) }
        for (groupItems in groups.values) {
            checkCancelled()
            groupItems.sortBy { it.index }
            val root = groupItems.first()

            val groupNeedsWork = groupItems.any { it.state != ItemState.DONE && it.state != ItemState.SKIPPED }
            if (groupNeedsWork) {
                when (root.itemKind) {
                    OpItemKind.TREE_DELETE -> runTreeDelete(ctx, root)
                    OpItemKind.TREE_MOVE -> runTreeMove(ctx, root, defaultPolicy)
                    else -> runCopyGroup(ctx, groupItems, defaultPolicy)
                }
            }
        }

        if (record.kind == OpKind.MOVE) {
            ctx.emptiedSourceDirs.sortedByDescending { it.length }.forEach { dir ->
                tryOrNull { Files.deleteIfExists(parsePath(dir)) }
            }
        }

        val stats = FileOpStats(ctx.c.ok, ctx.c.failed, ctx.c.skipped)
        journal.finishOp(
            record.id, OpState.FINISHED,
            stats.ok, stats.failed, stats.skipped,
            summaryMessage(record.kind, stats)
        )
        journal.prune()
        return stats
    }

    private fun summaryMessage(kind: OpKind, stats: FileOpStats): String {
        val label = when (kind) {
            OpKind.COPY -> "نسخ"
            OpKind.MOVE -> "نقل"
            OpKind.DELETE -> "حذف"
            else -> "عملية"
        }
        return "$label: ${stats.ok} ناجح، ${stats.failed} فاشل، ${stats.skipped} متخطى"
    }

    private fun runTreeDelete(ctx: RunContext, item: OpItemRecord) {
        ctx.progress(item.index, parsePath(item.src).fileName.toString())
        markRunning(ctx, item)
        try {
            AtomicIO.deleteTree(parsePath(item.src), checkCancelled = ::checkCancelled)
            journal.updateItem(ctx.record.id, item.index, ItemState.DONE)
            ctx.c.ok++
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            failItem(ctx, item, e.message ?: "تعذر الحذف")
        }
    }

    /**
     * نقل شجرة داخل الوحدة نفسها: إعادة تسمية ذرية واحدة (مع قرار التعارض إن وُجد هدف).
     *
     * حالة خاصة (3.2): كلا المصدر والهدف مجلدات + REPLACE: كان الاستبدال
     * الذري يزيح شجرة الهدف كلها ويحذفها فتفقد ملفات موجودة في الهدف وليست في
     * المصدر (سلوك COPY+REPLACE = دمج). يُحوَّل النقل هنا إلى مجموعة عناصر
     * عادية (مجلدات DIR_CREATE وملفات FILE بنقل إعادة تسمية لكل ملف) فيدمج
     * المصدر فوق الهدف متخطياً/مستبدلاً الملفات المتعارضة فقط. التبديل الذري
     * الكامل يبقى لملف↔ملف ولملف فوق غير مجلد.
     */
    private suspend fun runTreeMove(ctx: RunContext, item: OpItemRecord, defaultPolicy: ConflictPolicy) {
        val src = parsePath(item.src)
        val dst = parsePath(item.dst)
        ctx.progress(item.index, src.fileName.toString())
        if (src == dst) {
            skipItem(ctx, item, "المصدر والوجهة نفس العنصر")
            return
        }
        val srcIsDir = Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)
        val dstIsDir = Files.exists(dst, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(dst, LinkOption.NOFOLLOW_LINKS)
        if (srcIsDir && dstIsDir && defaultPolicy == ConflictPolicy.REPLACE) {
            mergeTreeMove(ctx, item, src, dst)
            return
        }
        var effectiveDst = dst
        var replaceExisting = false
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
            when (decide(ctx, ConflictRequest(src, dst, srcIsDir), defaultPolicy)) {
                ConflictPolicy.REPLACE -> {
                    if (srcIsDir && dstIsDir) {
                        // «استبدال» مجلد بمجلد (من الحوار أيضاً لا من السياسة الافتراضية
                        // فقط) = دمج؛ التبديل الكامل كان يحذف محتوى الوجهة غير المتعارض.
                        mergeTreeMove(ctx, item, src, dst)
                        return
                    }
                    if (dstIsDir) {
                        // A directory is never a replaceable "file": refuse instead of wiping the tree.
                        failItem(ctx, item, "refusing to replace a directory with a file")
                        return
                    }
                    replaceExisting = true
                }
                ConflictPolicy.KEEP_BOTH -> effectiveDst = uniqueName(dst, ctx)
                ConflictPolicy.SKIP -> {
                    skipItem(ctx, item, "الوجهة موجودة")
                    return
                }
                ConflictPolicy.ASK -> return
            }
        }
        markRunning(ctx, item)
        try {
            effectiveDst.parent?.let { Files.createDirectories(it) }
            moveReplacing(src, effectiveDst, replaceExisting, checkCancelled = ::checkCancelled)
            journal.updateItem(ctx.record.id, item.index, ItemState.DONE, dst = effectiveDst.toString())
            ctx.c.ok++
            ctx.c.bytesDone += item.size
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            failItem(ctx, item, e.message ?: "تعذر النقل")
        }
    }

    /**
     * دمج نقل شجرة داخل الوحدة فوق مجلد هدف موجود: يُعاد بناء مجموعة العناصر
     * (مجلدات + ملفات) كما يفعل enumerate لعملية نسخ، لكن كل ملف يُنفَّذ بنقل
     * إعادة تسمية ذري داخل الوحدة (لا نسخة)، والتعارض على الملفات يُحسم بسياسة
     * REPLACE (استبدال الملف المتعارض فقط). المجلدات المتعارضة تبقى وتُستكمل.
     *
     * نقاط تفتيش في السجل: كل عنصر فرعي يُسجَّل [OpJournal.addItems] قبل
     * تنفيذه، والمسجَّل منجَزاً/متخطاً من تشغيل سابق لا يُعاد — انقطاع وسط
     * الدمج يُستأنف من حيث توقف بدل إعادة المرور على الشجرة من أوله (الحد
     * الموثق في 0.7.7). المطابقة بين التعداد الحالي والمسجل بمسار المصدر،
     * فتغيّر الشجرة بين التشغيلين (ملف أُضيف/حُذف يدوياً) لا يُفسد الاستئناف:
     * الجديد يُسجَّل ويُنفَّذ، والغياب يبقى بحالته المسجلة.
     */
    private suspend fun mergeTreeMove(ctx: RunContext, item: OpItemRecord, src: Path, dst: Path) {
        markRunning(ctx, item)
        val recordedBySrc = journal.items(item.opId)
            .filter { it.group == item.group && it.index >= MERGE_SUB_INDEX_BASE }
            .associateBy { it.src }
        // عناصر المجموعة: جذر DIR_CREATE (نستكمل المجلد الموجود) ثم متحتوياته.
        val subItems = ArrayList<OpItemRecord>()
        subItems += item.copy(itemKind = OpItemKind.DIR_CREATE, dst = dst.toString(), extra = "")
        enumerateTree(item.opId, item.group, src, dst, subItems, 0) {
            if (subItems.size > maxEnumeratedItems) throw IOException("enumeration exceeded $maxEnumeratedItems items; merge refused")
        }
        // الموائمة: المطابق بـ src يبقى بفهرسه وحالته (فهرس السجل هو الهوية)،
        // والجديد يأخذ أعلى فهرس في العملية كلها +1 — لا تصادم مع عناصر startOp
        // ولا مع نقاط تفتيش دمج مجموعة أخرى في العملية نفسها.
        var nextIndex = maxOf(
            journal.items(item.opId).maxOfOrNull { it.index } ?: -1,
            MERGE_SUB_INDEX_BASE - 1
        ) + 1
        val toRecord = ArrayList<OpItemRecord>()
        for (i in subItems.indices) {
            val fresh = subItems[i]
            val recorded = recordedBySrc[fresh.src]
            if (recorded == null) {
                val checkpointed = fresh.copy(index = nextIndex++)
                subItems[i] = checkpointed
                toRecord += checkpointed
            } else {
                subItems[i] = recorded
            }
        }
        // التسجيل قبل التنفيذ: انقطاع أثناء إلحاق السطر يعني «لم تُسجَّل» —
        // يُعاد تسجيلها وتنفيذها عند الاستئناف، وما أُتم قبل الانقطاع مسجل.
        journal.addItems(item.opId, toRecord)
        val freshNow = HashSet<String>().apply { toRecord.forEach { add(it.src) } }
        ctx.emptiedSourceDirs += src.toString()
        // PARTIAL: حُفظ حساب العدّادات القديم عمداً (الجذر وكل مجلد وملف عنصر ok منفصل)
        // لأن اختبارات MoveSemanticsTest تثبّته | MISSING: توحيد العدّ ليكون عنصراً واحداً
        // لكل TREE_MOVE كبقية المسارات يحتاج تعديل تلك الاختبارات معاً.
        val failedBefore = ctx.c.failed
        var rootOk = false
        for (sub in subItems) {
            checkCancelled()
            if (sub === subItems.first()) {
                // الجذر: الهدف موجود ومجلد — دمج لا حذف. المسجل منجَزاً من
                // تشغيل سابق يُعدّ فقط (الهدف موجود بموجب شرط الدمج أصلاً).
                if (sub.state == ItemState.DONE) {
                    rootOk = true
                    continue
                }
                Files.createDirectories(dst)
                journal.updateItem(item.opId, sub.index, ItemState.DONE)
                ctx.c.ok++
                rootOk = true
                continue
            }
            if (!rootOk) continue
            if (sub.state == ItemState.DONE || sub.state == ItemState.SKIPPED) {
                // منجز أو متخطى في تشغيل سابق (أو رابط رمزي مسجل): لا إعادة تنفيذ.
                // مجلداته المصدرية تظل ضمن تنظيف المصادر المفرَّغة عند النقل.
                if (sub.itemKind == OpItemKind.DIR_CREATE) ctx.emptiedSourceDirs += sub.src
                if (sub.state == ItemState.SKIPPED && sub.src in freshNow) ctx.c.skipped++
                continue
            }
            val subSrc = parsePath(sub.src)
            when (sub.itemKind) {
                OpItemKind.DIR_CREATE -> {
                    val subDst = if (sub.extra.isEmpty()) dst else dst.resolve(sub.extra)
                    if (Files.exists(subDst, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isDirectory(subDst, LinkOption.NOFOLLOW_LINKS)) {
                            journal.updateItem(item.opId, sub.index, ItemState.DONE)
                            ctx.c.ok++
                        } else {
                            // مجلد يحل محل ملف موجود: يُزاح الملف بالاستبدال.
                            journal.updateItem(item.opId, sub.index, ItemState.RUNNING)
                            try {
                                AtomicIO.deleteTree(subDst)
                                Files.createDirectories(subDst)
                                journal.updateItem(item.opId, sub.index, ItemState.DONE)
                                ctx.c.ok++
                            } catch (e: OperationCancelledException) {
                                throw e
                            } catch (e: Exception) {
                                failItem(ctx, sub, e.message ?: "تعذر الحذف")
                            }
                        }
                    } else {
                        journal.updateItem(item.opId, sub.index, ItemState.RUNNING)
                        try {
                            Files.createDirectories(subDst)
                            journal.updateItem(item.opId, sub.index, ItemState.DONE)
                            ctx.c.ok++
                        } catch (e: OperationCancelledException) {
                            throw e
                        } catch (e: IOException) {
                            failItem(ctx, sub, e.message ?: "mkdir failed")
                        }
                    }
                    ctx.emptiedSourceDirs += sub.src
                }

                OpItemKind.FILE -> {
                    val subDst = if (sub.extra.isEmpty()) dst else dst.resolve(sub.extra)
                    mergeMoveFile(ctx, sub, subSrc, subDst)
                }

                else -> {}
            }
        }
        // العنصر الأصلي يُعلَّم بعد انتهاء الحلقة كلها فقط (قبلها كان يُعلَّم DONE بعد
        // إنشاء الجذر فيعدّ الاستئناف المجموعة منتهية وتبقى ملفات غير منقولة).
        // عند فشل عناصر فرعية يبقى FAILED فيعيد الاستئناف الدمج (المنقول لا يعود)
        // ولا يُعدّ في العدّادات مرة ثانية (failItem عدّ كل فشل أصلاً).
        val subFailed = ctx.c.failed - failedBefore
        if (subFailed > 0) {
            journal.updateItem(item.opId, item.index, ItemState.FAILED, error = "تعذر نقل $subFailed عنصراً أثناء الدمج")
        } else {
            journal.updateItem(item.opId, item.index, ItemState.DONE, dst = dst.toString())
        }
    }

    /** نقل ملف واحد داخل الدمج: إعادة تسمية ذرية، والتعارض استبدال للملف وحده. */
    private suspend fun mergeMoveFile(ctx: RunContext, item: OpItemRecord, src: Path, dst: Path) {
        if (src == dst) {
            skipItem(ctx, item, "المصدر والوجهة نفس الملف")
            return
        }
        markRunning(ctx, item)
        try {
            dst.parent?.let { Files.createDirectories(it) }
            moveReplacing(
                src,
                dst,
                replaceExisting = Files.exists(dst, LinkOption.NOFOLLOW_LINKS),
                checkCancelled = ::checkCancelled
            )
            journal.updateItem(item.opId, item.index, ItemState.DONE)
            ctx.c.ok++
            ctx.c.bytesDone += item.size
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            failItem(ctx, item, e.message ?: "تعذر النقل")
        }
    }

    /**
     * نقل ذري؛ عند [replaceExisting] يُزاح الهدف القديم إلى اسم مؤقت أولاً ولا يُحذف إلا بعد
     * نجاح النقل، وإن فشل النقل يعود الهدف القديم كما كان (لا فقدان بيانات).
     * (F-08) كل خطوتي النقل تدعمان عدم دعم ATOMIC_MOVE: fallback نقل عادي عبر
     * [AtomicIO.moveAtomicOrFallback] — بروتوكول الاستبدال يبقى سليماً على
     * FAT/exFAT وبعض مزودات FUSE بدل فشل كامل بلا مسار احتياطي. [move]
     * تُحقن للاختبار (محاكاة وسائط بلا نقل ذري)؛ الإنتاج يبقي الافتراضي.
     * داخلي (لا خاص) لاختبار مسار التراجع مباشرة في FileOpEngineTest.
     */
    internal fun moveReplacing(
        src: Path,
        dst: Path,
        replaceExisting: Boolean,
        checkCancelled: () -> Unit = {},
        move: (src: Path, dst: Path, replace: Boolean) -> Unit =
            { s, d, replace -> AtomicIO.moveAtomicOrFallback(s, d, replace) }
    ) {
        // نقل الوحدة الواحدة لا يمر عبر AtomicIO.moveVerified؛ لذلك يجب أن
        // يفحص الإلغاء هنا عند نقطة الالتزام أيضاً، لا عند بداية المجموعة فقط.
        checkCancelled()
        if (!replaceExisting) {
            AtomicIO.moveNoReplace(src, dst)
            return
        }
        val backup = dst.resolveSibling(".fm-replaced-" + UUID.randomUUID().toString().take(8))
        // إزاحة الهدف القديم (fallback نقل عادي إن لم يدعم النظام النقل الذري).
        // وسائط موضعية: الوسائط المسماة على دوال من نوع وظيفي غير مسموحة في K2.
        move(dst, backup, false)
        try {
            // إذا وصل الإلغاء بعد إزاحة الهدف القديم، أعده كما كان قبل الخروج.
            checkCancelled()
            move(src, dst, false)
        } catch (t: Throwable) {
            val restored = tryOrNull { move(backup, dst, false); true }
            if (restored != true) {
                t.addSuppressed(IOException("Rollback failed; original content kept at: $backup"))
                throw IOException("Replace failed and rollback failed; original content kept at: $backup", t)
            }
            throw t
        }
        // لا حذف للنسخة الاحتياطية (محتوى الهدف القديم) قبل دوام إعادة التسمية
        // الجديدة على الوسيط: انقطاع بعد حذف الاحتياطي وقبل دوام rename كان
        // يفقد المحتوى القديم والجديد معاً (انظر AtomicIO.fsyncDirectory).
        AtomicIO.fsyncDirectory(dst.parent)
        tryOrNull { AtomicIO.deleteTree(backup) }
    }

    /** فشل تحضير مجموعة (حذف عائق الوجهة): تُعلَّم عناصرها الباقية فاشلة بدل إسقاط الدفعة كلها. */
    private fun failGroup(ctx: RunContext, groupItems: List<OpItemRecord>, e: Exception) {
        groupItems.forEach {
            if (it.state != ItemState.DONE && it.state != ItemState.SKIPPED) {
                failItem(ctx, it, e.message ?: "تعذر الحذف")
            }
        }
    }

    /** مجموعة نسخ (أو نقل بين وحدتين): جذر DIR_CREATE أو ملف مستقل ثم محتواه. */
    private suspend fun runCopyGroup(ctx: RunContext, groupItems: List<OpItemRecord>, defaultPolicy: ConflictPolicy) {
        val record = ctx.record
        val root = groupItems.first()
        var rootDst = parsePath(root.dst)
        var skipWholeGroup = false

        if (root.itemKind == OpItemKind.DIR_CREATE && Files.exists(rootDst, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(rootDst, LinkOption.NOFOLLOW_LINKS)
        ) {
            when (decide(ctx, ConflictRequest(parsePath(root.src), rootDst, true), defaultPolicy)) {
                ConflictPolicy.REPLACE -> {
                    markRunning(ctx, root)
                    try {
                        AtomicIO.deleteTree(rootDst)
                    } catch (e: OperationCancelledException) {
                        throw e
                    } catch (e: Exception) {
                        failGroup(ctx, groupItems, e)
                        return
                    }
                }
                ConflictPolicy.KEEP_BOTH -> {
                    rootDst = uniqueName(rootDst, ctx)
                    journal.updateItem(record.id, root.index, ItemState.PENDING, dst = rootDst.toString())
                }
                ConflictPolicy.SKIP -> skipWholeGroup = true
                ConflictPolicy.ASK -> {}
            }
        }

        if (skipWholeGroup) {
            groupItems.forEach {
                if (it.state != ItemState.SKIPPED && it.state != ItemState.DONE) {
                    skipItem(ctx, it, "تخطي المجموعة")
                }
            }
            return
        }

        val crossMove = record.kind == OpKind.MOVE
        if (crossMove && root.itemKind == OpItemKind.DIR_CREATE) {
            // كل مجلدات المصدر (الجذر والمتداخلة) تُحذف بعد إفراغها: إضافة الجذر
            // وحده كانت تترك الهيكل المجلدي المتداخل فارغاً — Files.deleteIfExists
            // على الجذر يفشل بـ DirectoryNotEmptyException (يُبتلع) فتُخلَّف
            // هيئة فارغة والعملية تُعلن نجاحاً. الحذف الأعمق أولاً يعمل بالفعل.
            groupItems.forEach { item ->
                if (item.itemKind == OpItemKind.DIR_CREATE) ctx.emptiedSourceDirs += item.src
            }
        }

        val subtreeOverrides = ArrayList<Pair<String, Path>>()
        // On resume, rebuild KEEP_BOTH overrides from the journal: a completed folder
        // whose persisted dst differs from the planned dst was redirected earlier.
        for (item in groupItems) {
            if (item.itemKind != OpItemKind.DIR_CREATE || item.state != ItemState.DONE || item.extra.isEmpty()) continue
            if (item.dst != rootDst.resolve(item.extra).toString()) {
                subtreeOverrides += item.extra to parsePath(item.dst)
            }
        }

        // أعمق تجاوز «إبقاء الاثنين» يطابق المسار النسبي؛ يُستبدل بادئته بالمجلد البديل
        // (لا يُلصق المسار النسبي كاملاً تحته فيتكرر اسم المجلد).
        fun destinationOf(rel: String): Path {
            val override = subtreeOverrides
                .filter { rel == it.first || rel.startsWith(it.first + "/") }
                .maxByOrNull { it.first.length }
                ?: return if (rel.isEmpty()) rootDst else rootDst.resolve(rel)
            val rest = rel.removePrefix(override.first).removePrefix("/")
            return if (rest.isEmpty()) override.second else override.second.resolve(rest)
        }

        for (item in groupItems) {
            checkCancelled()
            if (item.state == ItemState.DONE || item.state == ItemState.SKIPPED) continue
            val src = parsePath(item.src)
            when (item.itemKind) {
                OpItemKind.DIR_CREATE -> {
                    val rel = item.extra
                    val dst = destinationOf(rel)
                    if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isDirectory(dst, LinkOption.NOFOLLOW_LINKS)) {
                            when (decide(ctx, ConflictRequest(src, dst, true), defaultPolicy)) {
                                ConflictPolicy.REPLACE -> {
                                    markRunning(ctx, item)
                                    try {
                                        AtomicIO.deleteTree(dst)
                                        Files.createDirectories(dst)
                                        journal.updateItem(record.id, item.index, ItemState.DONE)
                                        ctx.c.ok++
                                    } catch (e: OperationCancelledException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failItem(ctx, item, e.message ?: "تعذر الحذف")
                                    }
                                }
                                ConflictPolicy.KEEP_BOTH -> {
                                    val alternative = uniqueName(dst, ctx)
                                    subtreeOverrides += rel to alternative
                                    // Create the directory before recording DONE so a persisted
                                    // alternate path always exists.
                                    try {
                                        Files.createDirectories(alternative)
                                        journal.updateItem(record.id, item.index, ItemState.DONE, dst = alternative.toString())
                                        ctx.c.ok++
                                    } catch (e: IOException) {
                                        subtreeOverrides.removeAll { it.first == rel }
                                        failItem(ctx, item, e.message ?: "mkdir failed")
                                    }
                                }
                                else -> {
                                    skipItem(ctx, item, "الوجهة ملف موجود")
                                }
                            }
                        }
                    } else {
                        journal.updateItem(record.id, item.index, ItemState.RUNNING)
                        try {
                            Files.createDirectories(dst)
                            journal.updateItem(record.id, item.index, ItemState.DONE)
                            ctx.c.ok++
                        } catch (e: OperationCancelledException) {
                            throw e
                        } catch (e: IOException) {
                            failItem(ctx, item, e.message ?: "mkdir failed")
                        }
                    }
                }

                OpItemKind.FILE -> {
                    val dst = if (groupItems.size == 1) {
                        parsePath(item.dst)
                    } else {
                        destinationOf(item.extra)
                    }
                    copyOne(ctx, item, src, dst, defaultPolicy, crossMove)
                }

                else -> {}
            }
        }
    }

    /** نسخ/نقل ملف واحد بقرار التعارض (ملف مستقل أو داخل شجرة). */
    private suspend fun copyOne(
        ctx: RunContext,
        item: OpItemRecord,
        src: Path,
        dst: Path,
        defaultPolicy: ConflictPolicy,
        crossMove: Boolean
    ) {
        val record = ctx.record
        val baseBytes = ctx.c.bytesDone
        fun report(delta: Long) {
            ctx.progress(item.index, src.fileName.toString(), baseBytes + delta)
        }

        if (src == dst) {
            // النسخ داخل المجلد نفسه: نسخة باسم بديل «اسم (1).امتداد» كما في
            // مديري الملفات؛ النقل داخل المجلد نفسه لا معنى له فيبقى تخطياً (4.5).
            if (ctx.record.kind == OpKind.COPY) {
                val alternative = uniqueName(dst, ctx)
                journal.updateItem(record.id, item.index, ItemState.RUNNING, dst = alternative.toString())
                try {
                    alternative.parent?.let { Files.createDirectories(it) }
                    AtomicIO.copyAtomic(src, alternative, replace = false, checkCancelled = ::checkCancelled)
                    journal.updateItem(record.id, item.index, ItemState.DONE, dst = alternative.toString())
                    ctx.c.ok++
                } catch (e: OperationCancelledException) {
                    throw e
                } catch (e: Exception) {
                    failItem(ctx, item, e.message ?: "تعذر نسخ الملف")
                }
            } else {
                skipItem(ctx, item, "المصدر والوجهة نفس الملف")
            }
            return
        }

        var effectiveDst = dst
        var replace = false
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
            when (decide(ctx, ConflictRequest(src, dst, false), defaultPolicy)) {
                ConflictPolicy.REPLACE -> replace = true
                ConflictPolicy.KEEP_BOTH -> effectiveDst = uniqueName(dst, ctx)
                ConflictPolicy.SKIP -> {
                    skipItem(ctx, item, "الوجهة موجودة")
                    return
                }
                ConflictPolicy.ASK -> return
            }
        }

        markRunning(ctx, item)
        try {
            effectiveDst.parent?.let { Files.createDirectories(it) }
            if (crossMove) {
                AtomicIO.moveVerified(
                    src, effectiveDst, sameVolume = false, replace = replace,
                    onProgress = { delta -> report(delta) },
                    checkCancelled = ::checkCancelled,
                    dropPageCache = dropPageCache
                )
            } else {
                AtomicIO.copyAtomic(
                    src, effectiveDst, replace = replace,
                    onProgress = { delta -> report(delta) },
                    checkCancelled = ::checkCancelled,
                    preserveModifiedTime = true
                )
            }
            journal.updateItem(record.id, item.index, ItemState.DONE)
            ctx.c.ok++
            ctx.c.bytesDone += item.size
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            failItem(ctx, item, e.message ?: "تعذر نسخ الملف")
            ctx.c.bytesDone += item.size
        }
    }

    private suspend fun decide(
        ctx: RunContext,
        request: ConflictRequest,
        defaultPolicy: ConflictPolicy
    ): ConflictPolicy {
        if (defaultPolicy != ConflictPolicy.ASK) return defaultPolicy
        ctx.cachedDecision?.let { return it.policy }
        val decision = resolveConflict(request)
        if (decision.applyToAll) ctx.cachedDecision = decision
        return decision.policy
    }

    /** اسم بديل «اسم (1).امتداد» لا يتعارض مع موجود ولا مع ما أُنشئ في الدفعة نفسها. */
    private fun uniqueName(dst: Path, ctx: RunContext): Path {
        val parent = dst.parent ?: return dst
        val key = parent.toString()
        val taken = ctx.takenNames.getOrPut(key) { HashSet<String>() }
        taken.add(dst.fileName.toString().lowercase())
        val name = dst.fileName.toString()
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 1
        var candidate = parent.resolve("$stem ($n)$ext")
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || !taken.add(candidate.fileName.toString().lowercase())) {
            n++
            candidate = parent.resolve("$stem ($n)$ext")
        }
        return candidate
    }

    companion object {
        /**
         * (F-11) سقف التعداد الافتراضي: عناصر الدفعات (ملفات/مجلدات) فوقه تُرفض
         * العملية برسالة واضحة بدل استهلاك ذاكرة غير محدود. قابل للضبط عبر
         * [FileOpEngine.maxEnumeratedItems] لمن يحتاج عمليات أكبر.
         */
        const val DEFAULT_MAX_ENUMERATED_ITEMS = 500_000

        /**
         * نطاق فهارس نقاط تفتيش الدمج: بعيد عن فهارس عناصر startOp (عدد عناصر
         * التعداد المعقول لا يبلغ نصف مدى Int) فلا يكتب updateItem حالة عنصر
         * آخر — عنصر بفهرس غير مسجل يُتجاهل في السجل. نقاط التفتيش تُسجَّل الآن
         * عبر addItems بفهارس فريدة من هذا النطاق، وبعده إن امتلأ
         * (max(index) + 1 على العملية كلها).
         */
        private const val MERGE_SUB_INDEX_BASE = Int.MAX_VALUE / 2

        fun newOpId(): String = UUID.randomUUID().toString().take(8)
    }
}

internal inline fun <T> tryOrNull(block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    null
}
