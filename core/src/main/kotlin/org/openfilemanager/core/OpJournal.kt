package org.openfilemanager.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.OutputStreamWriter
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.UUID

enum class OpKind { COPY, MOVE, DELETE, RENAME, EXTRACT, COMPRESS }

enum class OpState { RUNNING, FINISHED, FAILED, CANCELLED, INTERRUPTED }

enum class ItemState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** نوع العنصر داخل العملية (يحدد كيف يُنفَّذ عند الاستئناف). */
enum class OpItemKind { FILE, DIR_CREATE, TREE_MOVE, TREE_DELETE }

data class OpRecord(
    val id: String,
    val kind: OpKind,
    val state: OpState,
    val sources: List<String>,
    val targetDir: String,
    val createdAt: Long,
    val finishedAt: Long = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val totalBytes: Long = 0,
    val message: String = ""
) {
}

/** عنصر عمل قابل للاستئناف. */
data class OpItemRecord(
    val opId: String,
    val index: Int,
    val itemKind: OpItemKind,
    val state: ItemState,
    val src: String,
    val dst: String,
    val extra: String = "",
    val extra2: String = "",
    val size: Long = 0,
    val error: String = "",
    val group: Int = 0
)

/** سجل العمليات الطويلة. */
interface OpJournal {
    fun startOp(record: OpRecord, items: List<OpItemRecord>)
    /**
     * تسجيل عناصر جديدة تُنشأ وقت التنفيذ (نقاط تفتيش الدمج في نقل مجلد فوق
     * مجلد): تُلحق بالعملية قبل تنفيذها فلا يعتمد الاستئناف على تعداد غير
     * موثق. الفهارس يجب ألا تصطدم بعناصر [startOp] الموجودة.
     */
    fun addItems(opId: String, items: List<OpItemRecord>)
    fun updateItem(
        opId: String,
        index: Int,
        state: ItemState,
        extra: String = "",
        error: String = "",
        dst: String = ""
    )
    fun finishOp(opId: String, state: OpState, ok: Int, failed: Int, skipped: Int, message: String = "")
    fun ops(): List<OpRecord>
    fun op(id: String): OpRecord?
    fun items(opId: String): List<OpItemRecord>
    fun remove(opId: String)
    /** يبقي آخر [keep] عملية منتهية فقط ويحذف الباقي (منع نمو السجل إلى ما لا نهاية). */
    fun prune(keep: Int = 20)
}

/** ترميز الحقول حتى لا تكسر السطر (المسار قد يحوي أي محارف نظرياً). */
internal object Tsv {
    fun encode(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    fun decode(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(c)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}

/** سجل ملفات: مجلد يحوي ملف `op-<id>.log` لكل عملية. */
class FileOpJournal(private val dir: Path) : OpJournal {
    private val lock = Any()

    /** فهرس عناصر كل عملية مفتوحة: معرّف العملية → (فهرس العنصر → آخر حالة له). */
    private val itemIndex = HashMap<String, HashMap<Int, OpItemRecord>>()

    init {
        Files.createDirectories(dir)
    }

    private fun file(opId: String): Path {
        require(opId.all { it.isLetterOrDigit() || it == '-' }) { "bad op id" }
        return dir.resolve("op-$opId.log")
    }

    override fun startOp(record: OpRecord, items: List<OpItemRecord>): Unit = synchronized(lock) {
        val f = file(record.id)
        require(record.state == OpState.RUNNING) { "new operation must start in RUNNING state" }
        require(items.all { it.opId == record.id }) { "operation item id does not match record id" }
        require(items.map { it.index }.toSet().size == items.size) { "duplicate operation item index" }
        if (Files.exists(f)) throw java.nio.file.FileAlreadyExistsException(f.toString())

        // Build a complete journal beside the final file, force its contents, then
        // publish it without replacing an existing operation. A process death while
        // writing therefore cannot destroy a previous record with the same opId.
        val tmp = dir.resolve(".fm-journal-${UUID.randomUUID()}.tmp")
        try {
            java.nio.channels.FileChannel.open(
                tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
            ).use { channel ->
                val out = OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8)
                val head = listOf(
                    "V1", Tsv.encode(record.id), record.kind.name, OpState.RUNNING.name,
                    record.createdAt.toString(), Tsv.encode(record.targetDir), Tsv.encode(record.sources.joinToString("\u0001")),
                    record.totalBytes.toString()
                ).joinToString("\t")
                out.write(head); out.write('\n'.code)
                items.forEach { out.write(itemLine(it)); out.write('\n'.code) }
                out.flush()
                channel.force(true)
            }
            // No REPLACE_EXISTING: a repeated UUID must never erase an older journal.
            Files.move(tmp, f)
            AtomicIO.fsyncDirectory(dir)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
        itemIndex[record.id] = HashMap<Int, OpItemRecord>(items.size * 2).apply {
            items.forEach { put(it.index, it) }
        }
    }

    override fun addItems(opId: String, items: List<OpItemRecord>): Unit = synchronized(lock) {
        if (items.isEmpty()) return
        val f = file(opId)
        if (!Files.exists(f)) throw IOException("operation journal does not exist: $opId")
        require(items.all { it.opId == opId }) { "operation item id does not match journal id" }
        require(items.map { it.index }.toSet().size == items.size) { "duplicate item index in append" }
        val parsed = parse(f)
        require(parsed.first?.state == OpState.RUNNING) { "cannot append items to a finished operation" }
        val idx = itemIndex.getOrPut(opId) { parsed.second.associateByTo(HashMap()) { it.index } }
        require(items.none { it.index in idx }) { "operation item index already exists" }

        // Validate the entire batch before touching disk so an invalid dynamic
        // checkpoint cannot leave a partially appended journal.
        appendDurably(f, items.joinToString("") { itemLine(it) + "\n" })
        items.forEach { idx[it.index] = it }
    }

    /** إلحاق سطر/أسطر بالسجل؛ [force] يستدعي fdatasync حتى لا تضيع ذيول السجل بعد انقطاع الطاقة. */
    private fun appendDurably(f: Path, text: String, force: Boolean = true) {
        FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            // ذيل مبتور بلا \n (انقطاع وسط الكتابة): نفصله بسطر جديد حتى لا يلتصق به السطر التالي فيضيعا معاً.
            var payload = text
            val size = ch.size()
            if (size > 0) {
                val last = java.nio.ByteBuffer.allocate(1)
                FileChannel.open(f, StandardOpenOption.READ).use { it.read(last, size - 1) }
                if (last.get(0) != '\n'.code.toByte()) payload = "\n" + payload
            }
            val buf = java.nio.ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8))
            while (buf.hasRemaining()) ch.write(buf)
            if (force) ch.force(false)
        }
    }

    /** عدد الأسطر التالفة التي تجاوزتها القراءة منذ إنشاء الكائن (للتشخيص). */
    @Volatile
    var corruptLineCount: Int = 0
        private set

    private fun itemLine(it: OpItemRecord): String = listOf(
        "I", it.index.toString(), it.group.toString(), it.itemKind.name, it.state.name,
        Tsv.encode(it.src), Tsv.encode(it.dst), Tsv.encode(it.extra), Tsv.encode(it.extra2),
        it.size.toString(), Tsv.encode(it.error)
    ).joinToString("\t")

    override fun updateItem(opId: String, index: Int, state: ItemState, extra: String, error: String, dst: String): Unit = synchronized(lock) {
        val f = file(opId)
        if (!Files.exists(f)) {
            itemIndex.remove(opId)
            return
        }
        val idx = itemIndex.getOrPut(opId) {
            val parsed = parse(f).second.associateByTo(HashMap()) { it.index }
            if (parsed.isEmpty()) HashMap() else parsed
        }
        val current = idx[index] ?: return
        val updated = current.copy(
            state = state,
            dst = dst.ifEmpty { current.dst },
            extra = extra.ifEmpty { current.extra },
            error = error.ifEmpty { current.error }
        )
        // الحالات النهائية (DONE/FAILED/SKIPPED) تُدوَّم: ضياعها يجعل الاسترداد يعيد تنفيذ عنصر أُنجز.
        val terminal = state == ItemState.DONE || state == ItemState.FAILED || state == ItemState.SKIPPED
        appendDurably(f, itemLine(updated) + "\n", force = terminal)
        idx[index] = updated
    }

    override fun finishOp(opId: String, state: OpState, ok: Int, failed: Int, skipped: Int, message: String): Unit = synchronized(lock) {
        val f = file(opId)
        if (!Files.exists(f)) return
        val line = listOf("F", state.name, System.currentTimeMillis().toString(), ok.toString(), failed.toString(), skipped.toString(), Tsv.encode(message)).joinToString("\t")
        appendDurably(f, line + "\n")
        // تحرير فهرس العملية المنتهية: لا يبقى في الذاكرة إلا ما يجري تنفيذه.
        itemIndex.remove(opId)
    }

    override fun ops(): List<OpRecord> = synchronized(lock) {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.newDirectoryStream(dir, "op-*.log").use { stream ->
            stream.mapNotNull { f -> parse(f).first }
                .sortedByDescending { it.createdAt }
        }
    }

    override fun op(id: String): OpRecord? = synchronized(lock) {
        val f = file(id)
        if (Files.exists(f)) parse(f).first else null
    }

    override fun items(opId: String): List<OpItemRecord> = synchronized(lock) {
        val f = file(opId)
        if (Files.exists(f)) parse(f).second else emptyList()
    }

    override fun remove(opId: String): Unit = synchronized(lock) {
        itemIndex.remove(opId)
        Files.deleteIfExists(file(opId))
    }

    override fun prune(keep: Int): Unit = synchronized(lock) {
        val finished = ops().filter { it.state != OpState.RUNNING && it.state != OpState.INTERRUPTED }
        finished.drop(keep).forEach { remove(it.id) }
    }

    /** يعيد (سجل العملية، العناصر بآخر حالة). يتجاهل السطور التالفة والسطر الأخير غير المكتمل. */
    private fun parse(f: Path): Pair<OpRecord?, List<OpItemRecord>> {
        if (!Files.exists(f)) return null to emptyList()
        val text = String(Files.readAllBytes(f), StandardCharsets.UTF_8)
        var record: OpRecord? = null
        val items = HashMap<Int, OpItemRecord>()
        var opId = ""
        val complete = text.endsWith("\n")
        val lines = text.split('\n')
        for ((lineNo, raw) in lines.withIndex()) {
            if (raw.isEmpty()) continue
            if (lineNo == lines.lastIndex && !complete) continue
            val cols = raw.split('\t')
            try {
                when (cols.getOrNull(0)) {
                    "V1" -> {
                        opId = Tsv.decode(cols[1])
                        record = OpRecord(
                            id = opId,
                            kind = OpKind.valueOf(cols[2]),
                            state = OpState.valueOf(cols[3]),
                            sources = Tsv.decode(cols.getOrElse(6) { "" }).split('\u0001').filter { it.isNotEmpty() },
                            targetDir = Tsv.decode(cols.getOrElse(5) { "" }),
                            createdAt = cols.getOrElse(4) { "0" }.toLong(),
                            totalBytes = cols.getOrElse(7) { "0" }.toLongOrNull() ?: 0
                        )
                    }
                    "I" -> {
                        val idx = cols[1].toInt()
                        items[idx] = OpItemRecord(
                            opId = opId,
                            index = idx,
                            group = cols.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
                            itemKind = OpKind_safe(cols[3]),
                            state = runCatching { ItemState.valueOf(cols[4]) }.getOrDefault(ItemState.PENDING),
                            src = Tsv.decode(cols.getOrElse(5) { "" }),
                            dst = Tsv.decode(cols.getOrElse(6) { "" }),
                            extra = Tsv.decode(cols.getOrElse(7) { "" }),
                            extra2 = Tsv.decode(cols.getOrElse(8) { "" }),
                            size = cols.getOrElse(9) { "0" }.toLongOrNull() ?: 0,
                            error = Tsv.decode(cols.getOrElse(10) { "" })
                        )
                    }
                    "F" -> {
                        record = record?.copy(
                            state = OpState.valueOf(cols[1]),
                            finishedAt = cols.getOrElse(2) { "0" }.toLongOrNull() ?: 0,
                            ok = cols.getOrElse(3) { "0" }.toIntOrNull() ?: 0,
                            failed = cols.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
                            skipped = cols.getOrElse(5) { "0" }.toIntOrNull() ?: 0,
                            message = Tsv.decode(cols.getOrElse(6) { "" })
                        )
                    }
                }
            } catch (e: Exception) {
                // سطر تالف: يُتجاوز ويُعدّ، ولا يُسقط قراءة بقية السجل.
                corruptLineCount++
            }
        }
        return record to items.keys.sorted().map { items.getValue(it) }
    }

    private fun OpKind_safe(name: String): OpItemKind =
        runCatching { OpItemKind.valueOf(name) }.getOrDefault(OpItemKind.FILE)
}

/** سجل في الذاكرة للاختبارات وللمحاكاة. */
class MemoryOpJournal : OpJournal {
    private val lock = Any()
    private val opMap = LinkedHashMap<String, OpRecord>()
    private val itemsMap = HashMap<String, MutableList<OpItemRecord>>()

    override fun startOp(record: OpRecord, items: List<OpItemRecord>): Unit = synchronized(lock) {
        require(record.state == OpState.RUNNING) { "new operation must start in RUNNING state" }
        require(items.all { it.opId == record.id }) { "operation item id does not match record id" }
        require(items.map { it.index }.toSet().size == items.size) { "duplicate operation item index" }
        if (record.id in opMap) throw java.nio.file.FileAlreadyExistsException(record.id)
        opMap[record.id] = record
        itemsMap[record.id] = items.toMutableList()
    }

    override fun addItems(opId: String, items: List<OpItemRecord>): Unit = synchronized(lock) {
        if (items.isEmpty()) return
        val existing = itemsMap[opId] ?: throw IOException("operation journal does not exist: $opId")
        require(opMap[opId]?.state == OpState.RUNNING) { "cannot append items to a finished operation" }
        require(items.all { it.opId == opId }) { "operation item id does not match journal id" }
        require(items.map { it.index }.toSet().size == items.size) { "duplicate item index in append" }
        val used = existing.mapTo(HashSet()) { it.index }
        require(items.none { it.index in used }) { "operation item index already exists" }
        existing.addAll(items)
    }

    override fun updateItem(opId: String, index: Int, state: ItemState, extra: String, error: String, dst: String): Unit = synchronized(lock) {
        val list = itemsMap[opId] ?: return
        // المطابقة بحقل الفهرس لا بموضع القائمة: عناصر الدمج المسجلة (addItems)
        // بفهارس أعلى من نطاق التعداد فموضعها في القائمة لا يساوي فهرسها.
        val pos = list.indexOfFirst { it.index == index }
        if (pos < 0) return
        val cur = list[pos]
        list[pos] = cur.copy(
            state = state,
            dst = dst.ifEmpty { cur.dst },
            extra = extra.ifEmpty { cur.extra },
            error = error.ifEmpty { cur.error }
        )
    }

    override fun finishOp(opId: String, state: OpState, ok: Int, failed: Int, skipped: Int, message: String): Unit = synchronized(lock) {
        opMap[opId] = opMap[opId]?.copy(state = state, finishedAt = System.currentTimeMillis(), ok = ok, failed = failed, skipped = skipped, message = message) ?: return
    }

    override fun ops(): List<OpRecord> = synchronized(lock) { opMap.values.sortedByDescending { it.createdAt } }
    override fun op(id: String): OpRecord? = synchronized(lock) { opMap[id] }
    override fun items(opId: String): List<OpItemRecord> = synchronized(lock) { itemsMap[opId]?.toList() ?: emptyList() }
    override fun remove(opId: String): Unit = synchronized(lock) { opMap.remove(opId); itemsMap.remove(opId) }
    override fun prune(keep: Int): Unit = synchronized(lock) {
        ops().filter { it.state != OpState.RUNNING && it.state != OpState.INTERRUPTED }
            .drop(keep).forEach { remove(it.id) }
    }
}

/**
 * جسر [RenameJournal] (موجودة أصلاً للتسمية على مرحلتين) إلى سجل العمليات:
 * تُسجَّل المرحلتان كعنصر واحد لكل ملف فتُستردّ التسمية المقطوعة أو يُتراجع عنها.
 */
class JournalRenameAdapter(
    private val journal: OpJournal,
    private val opId: String,
    private val steps: List<RenameStep>
) : RenameJournal {
    private var current = -1

    fun begin() {
        journal.startOp(
            OpRecord(opId, OpKind.RENAME, OpState.RUNNING, emptyList(), "", System.currentTimeMillis()),
            steps.mapIndexed { i, s ->
                OpItemRecord(
                    opId = opId, index = i, group = i,
                    itemKind = OpItemKind.FILE, state = ItemState.PENDING,
                    src = s.from.toString(), dst = s.to.toString(), extra = s.temp.toString()
                )
            }
        )
    }

    override fun record(phase: RenamePhase, step: RenameStep) {
        val index = steps.indexOfFirst { it.from == step.from && it.to == step.to }
        if (index < 0) return
        when (phase) {
            RenamePhase.TO_TEMP -> journal.updateItem(opId, index, ItemState.RUNNING)
            RenamePhase.TO_FINAL -> journal.updateItem(opId, index, ItemState.DONE)
        }
    }
}
