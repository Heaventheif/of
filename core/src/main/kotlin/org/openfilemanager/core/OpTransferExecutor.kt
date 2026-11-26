package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** عدّادات تنفيذ الدفعة (كانت Counters داخل [FileOpEngine]) — مشتركة مع المستخرجات. */
internal class Counters {
    var ok = 0
    var failed = 0
    var skipped = 0
    var bytesDone = 0L
}

/** سياق تشغيل دفعة واحدة (مستخرج من [FileOpEngine] بلا تغيير سلوك): عدّادات، قرار تعارض مخزّن (تطبيق على الكل)، أسماء بديلة مأخوذة، ومجلدات المصدر. */
internal class RunContext(
    val record: OpRecord,
    val itemCount: Int,
    val bytesTotal: Long,
    private val onProgress: (FileOpProgress) -> Unit
) {
    val c = Counters()
    var cachedDecision: ConflictDecision? = null
    val takenNames = HashMap<String, MutableSet<String>>()
    val emptiedSourceDirs = ArrayList<String>()

    fun progress(itemIndex: Int, current: String, bytesDoneOverride: Long? = null) {
        onProgress(FileOpProgress(itemIndex, itemCount, bytesDoneOverride ?: c.bytesDone, bytesTotal, current))
    }
}

/** منفّذ النقل/النسخ (مستخرج من [FileOpEngine] بلا تغيير سلوك): نقل شجرة داخل الوحدة (إعادة تسمية ذرية بقرار تعارض، أو دمج فوق مجلد موجود بنقاط تفتيش في. */
internal class OpTransferExecutor(
    private val journal: OpJournal,
    private val treeEnumerator: OpTreeEnumerator,
    private val maxEnumeratedItems: Int,
    private val dropPageCache: (Path) -> Unit,
    private val resolveConflict: suspend (ConflictRequest) -> ConflictDecision,
    private val parsePath: (String) -> Path,
    private val cancelCheck: () -> Unit
) {
    /** نقل شجرة داخل الوحدة نفسها: إعادة تسمية ذرية واحدة (مع قرار التعارض إن وُجد هدف). */
    internal suspend fun runTreeMove(ctx: RunContext, item: OpItemRecord, defaultPolicy: ConflictPolicy) {
        val src = parsePath(item.src)
        val dst = parsePath(item.dst)
        ctx.progress(item.index, src.fileName.toString())
        if (src == dst) {
            journal.skipItem(ctx, item, CoreOpCode.SAME_PATH)
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
                        journal.failItem(ctx, item, "refusing to replace a directory with a file")
                        return
                    }
                    replaceExisting = true
                }
                ConflictPolicy.KEEP_BOTH -> effectiveDst = uniqueName(dst, ctx)
                ConflictPolicy.SKIP -> {
                    journal.skipItem(ctx, item, CoreOpCode.DESTINATION_EXISTS)
                    return
                }
                ConflictPolicy.ASK -> return
            }
        }
        journal.markRunning(ctx, item)
        try {
            effectiveDst.parent?.let { Files.createDirectories(it) }
            moveReplacing(src, effectiveDst, replaceExisting, checkCancelled = cancelCheck)
            journal.updateItem(ctx.record.id, item.index, ItemState.DONE, dst = effectiveDst.toString())
            ctx.c.ok++
            ctx.c.bytesDone += item.size
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            journal.failItem(ctx, item, e.message ?: CoreOpCode.TRANSFER_FAILED)
        }
    }

    /** دمج نقل شجرة داخل الوحدة فوق مجلد هدف موجود. */
    private suspend fun mergeTreeMove(ctx: RunContext, item: OpItemRecord, src: Path, dst: Path) {
        journal.markRunning(ctx, item)
        val recordedBySrc = journal.items(item.opId)
            .filter { it.group == item.group && it.index >= MERGE_SUB_INDEX_BASE }
            .associateBy { it.src }
        // عناصر المجموعة: جذر DIR_CREATE (نستكمل المجلد الموجود) ثم متحتوياته.
        val subItems = ArrayList<OpItemRecord>()
        subItems += item.copy(itemKind = OpItemKind.DIR_CREATE, dst = dst.toString(), extra = "")
        treeEnumerator.enumerateTree(item.opId, item.group, src, dst, subItems, 0) {
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
            cancelCheck()
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
                                journal.failItem(ctx, sub, e.message ?: CoreOpCode.DELETE_FAILED)
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
                            journal.failItem(ctx, sub, e.message ?: "mkdir failed")
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
        // العنصر الأصلي يُعلَّم بعد انتهاء الحلقة كلها فقط (قبلها كان يُعلَّم DONE بعد إنشاء الجذر فيعدّ الاستئناف المجموعة منتهية وتبقى ملفات غير منقولة).
        val subFailed = ctx.c.failed - failedBefore
        if (subFailed > 0) {
            journal.updateItem(item.opId, item.index, ItemState.FAILED, error = CoreOpCode.message(CoreOpCode.MERGE_SUB_FAILED, subFailed))
        } else {
            journal.updateItem(item.opId, item.index, ItemState.DONE, dst = dst.toString())
        }
    }

    /** نقل ملف واحد داخل الدمج: إعادة تسمية ذرية، والتعارض استبدال للملف وحده. */
    private suspend fun mergeMoveFile(ctx: RunContext, item: OpItemRecord, src: Path, dst: Path) {
        if (src == dst) {
            journal.skipItem(ctx, item, CoreOpCode.SAME_FILE)
            return
        }
        journal.markRunning(ctx, item)
        try {
            dst.parent?.let { Files.createDirectories(it) }
            moveReplacing(
                src,
                dst,
                replaceExisting = Files.exists(dst, LinkOption.NOFOLLOW_LINKS),
                checkCancelled = cancelCheck
            )
            journal.updateItem(item.opId, item.index, ItemState.DONE)
            ctx.c.ok++
            ctx.c.bytesDone += item.size
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            journal.failItem(ctx, item, e.message ?: CoreOpCode.TRANSFER_FAILED)
        }
    }

    /** نقل ذري؛ عند [replaceExisting] يُزاح الهدف القديم إلى اسم مؤقت أولاً ولا يُحذف إلا بعد نجاح النقل، وإن فشل النقل يعود الهدف القديم كما كان. */
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
                journal.failItem(ctx, it, e.message ?: CoreOpCode.DELETE_FAILED)
            }
        }
    }

    /** مجموعة نسخ (أو نقل بين وحدتين): جذر DIR_CREATE أو ملف مستقل ثم محتواه. */
    internal suspend fun runCopyGroup(ctx: RunContext, groupItems: List<OpItemRecord>, defaultPolicy: ConflictPolicy) {
        val record = ctx.record
        val root = groupItems.first()
        var rootDst = parsePath(root.dst)
        var skipWholeGroup = false

        if (root.itemKind == OpItemKind.DIR_CREATE && Files.exists(rootDst, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(rootDst, LinkOption.NOFOLLOW_LINKS)
        ) {
            when (decide(ctx, ConflictRequest(parsePath(root.src), rootDst, true), defaultPolicy)) {
                ConflictPolicy.REPLACE -> {
                    journal.markRunning(ctx, root)
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
                    journal.skipItem(ctx, it, CoreOpCode.SKIP_GROUP)
                }
            }
            return
        }

        val crossMove = record.kind == OpKind.MOVE
        if (crossMove && root.itemKind == OpItemKind.DIR_CREATE) {
            // كل مجلدات المصدر (الجذر والمتداخلة) تُحذف بعد إفراغها: إضافة الجذر وحده كانت تترك الهيكل المجلدي المتداخل فارغاً — Files.deleteIfExists على الجذر…
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
            cancelCheck()
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
                                    journal.markRunning(ctx, item)
                                    try {
                                        AtomicIO.deleteTree(dst)
                                        Files.createDirectories(dst)
                                        journal.updateItem(record.id, item.index, ItemState.DONE)
                                        ctx.c.ok++
                                    } catch (e: OperationCancelledException) {
                                        throw e
                                    } catch (e: Exception) {
                                        journal.failItem(ctx, item, e.message ?: CoreOpCode.DELETE_FAILED)
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
                                        journal.failItem(ctx, item, e.message ?: "mkdir failed")
                                    }
                                }
                                else -> {
                                    journal.skipItem(ctx, item, CoreOpCode.DESTINATION_IS_FILE)
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
                            journal.failItem(ctx, item, e.message ?: "mkdir failed")
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
                    AtomicIO.copyAtomic(src, alternative, replace = false, checkCancelled = cancelCheck)
                    journal.updateItem(record.id, item.index, ItemState.DONE, dst = alternative.toString())
                    ctx.c.ok++
                } catch (e: OperationCancelledException) {
                    throw e
                } catch (e: Exception) {
                    journal.failItem(ctx, item, e.message ?: CoreOpCode.COPY_FAILED)
                }
            } else {
                journal.skipItem(ctx, item, CoreOpCode.SAME_FILE)
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
                    journal.skipItem(ctx, item, CoreOpCode.DESTINATION_EXISTS)
                    return
                }
                ConflictPolicy.ASK -> return
            }
        }

        journal.markRunning(ctx, item)
        try {
            effectiveDst.parent?.let { Files.createDirectories(it) }
            if (crossMove) {
                AtomicIO.moveVerified(
                    src, effectiveDst, sameVolume = false, replace = replace,
                    onProgress = { delta -> report(delta) },
                    checkCancelled = cancelCheck,
                    dropPageCache = dropPageCache
                )
            } else {
                AtomicIO.copyAtomic(
                    src, effectiveDst, replace = replace,
                    onProgress = { delta -> report(delta) },
                    checkCancelled = cancelCheck,
                    preserveModifiedTime = true
                )
            }
            journal.updateItem(record.id, item.index, ItemState.DONE)
            ctx.c.ok++
            ctx.c.bytesDone += item.size
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            journal.failItem(ctx, item, e.message ?: CoreOpCode.COPY_FAILED)
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
        // توحيد منطق الاسم الفريد عبر NameRules.uniqueName — كان مكرراً.
        val unique = NameRules.uniqueName(dst.fileName.toString()) { candidate ->
            Files.exists(parent.resolve(candidate), LinkOption.NOFOLLOW_LINKS) ||
                !taken.add(candidate.lowercase())
        }
        return parent.resolve(unique)
    }

    companion object {
        /** نطاق فهارس نقاط تفتيش الدمج. */
        private const val MERGE_SUB_INDEX_BASE = Int.MAX_VALUE / 2
    }
}
