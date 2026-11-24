package org.openfilemanager.core

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/** عنصر داخل دفعة التسمية: الاسم الحالي وهل هو مجلد (المجلدات لا امتداد لها). */
data class RenameInput(val name: String, val isDirectory: Boolean = false)

/**
 * مواصفة التسمية الجماعية (أربع خانات فقط، لا خطوة ترقيم).
 *
 * [template]  قالب حرفي: `%%` = الاسم الأصلي بلا امتداد، `##` = الرقم التسلسلي، وكل ما سواهما نص حرفي
 *             يُحفظ كما كُتب (مسافات، `%` و`#` المفردان، ...) دون أي تنظيف.
 * [extension] امتداد عام: فارغ = يبقى امتداد كل ملف كما هو. النقطة تُضاف تلقائياً.
 * [start]     رقم أول ملف؛ كل ملف تالٍ يزيد واحداً بالضبط.
 * [zeros]     عدد الأصفار الإضافية قبل الرقم: العرض = أرقام [start] + [zeros]، والرقم الأطول لا يُقتطع.
 */
data class BatchRenameSpec(
    val template: String,
    val extension: String = "",
    val start: Long = 1L,
    val zeros: Int = 0
)

/** أخطاء المواصفة نفسها (لا الأسماء): تمنع التنفيذ. */
enum class RenameSpecError { EMPTY_SELECTION, BAD_START, BAD_ZEROS }

enum class RenameStatus {
    /** سيُغيَّر اسمه. */
    OK,
    /** الاسم الجديد = الأصلي: لن يُلمس. */
    UNCHANGED,
    /** اسم غير صالح في نظام الملفات (انظر [RenameRow.problem]). */
    INVALID,
    /** الاسم الناتج مكرر داخل الدفعة نفسها. */
    DUPLICATE,
    /** يوجد عنصر آخر (غير محدد) بهذا الاسم في المجلد. */
    CONFLICT
}

/**
 * سطر معاينة = بالضبط ما سيُنفَّذ. [number] الرقم كما كُتب (بأصفاره)، و[extension] الامتداد
 * المستخدم (مع النقطة، أو فارغ).
 */
data class RenameRow(
    val index: Int,
    val original: String,
    val newName: String,
    val number: String,
    val extension: String,
    val status: RenameStatus,
    val problem: NameProblem? = null
) {
    val blocking: Boolean
        get() = status == RenameStatus.INVALID || status == RenameStatus.DUPLICATE || status == RenameStatus.CONFLICT
}

/** نتيجة المحرك الواحد: تُستعمل للمعاينة والتنفيذ معاً فلا اختلاف بينهما. */
class RenamePlan(val rows: List<RenameRow>, val specError: RenameSpecError? = null) {
    val blockingCount: Int = rows.count { it.blocking }
    val changedCount: Int = rows.count { it.status == RenameStatus.OK }
    val unchangedCount: Int = rows.count { it.status == RenameStatus.UNCHANGED }
    /** التنفيذ مسموح فقط بلا خطأ مواصفة ولا تعارض، ومع ما يُغيَّر فعلاً. */
    val canApply: Boolean get() = specError == null && blockingCount == 0 && changedCount > 0

    /** أزواج (قديم، جديد) للصفوف التي ستتغير فقط، بالترتيب نفسه. */
    fun renames(): List<Pair<String, String>> =
        rows.filter { it.status == RenameStatus.OK }.map { it.original to it.newName }
}

object BatchRename {
    const val MAX_ZEROS = 20
    private const val MAX_START_DIGITS = 18
    private val TOKEN = Regex("%%|##")

    /** خانة بداية الترقيم: فارغ = 1، أرقام فقط، وإلا null (غير صالحة). */
    fun parseStart(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return 1L
        if (t.length > MAX_START_DIGITS || !t.all { it in '0'..'9' }) return null
        return t.toLong()
    }

    /** خانة الأصفار: فارغ أو 0 = بلا أصفار، وإلا null إن كانت غير رقمية أو فوق [MAX_ZEROS]. */
    fun parseZeros(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        if (t.length > 3 || !t.all { it in '0'..'9' }) return null
        return t.toInt().takeIf { it <= MAX_ZEROS }
    }

    /** الامتداد العام: لا تُشترط النقطة، وأي نقاط في أوله تُزال كي لا يتضاعف الامتداد. */
    fun normalizeExtension(raw: String): String = raw.trim().trimStart('.')

    /** الرقم التسلسلي للعنصر ذي الفهرس [index]: عرض ثابت = أرقام البداية + الأصفار، بلا اقتطاع. */
    fun numberText(spec: BatchRenameSpec, index: Int): String {
        val width = spec.start.toString().length + spec.zeros.coerceIn(0, MAX_ZEROS)
        return (spec.start + index).toString().padStart(width, '0')
    }

    /**
     * يبني اسماً واحداً. مرور واحد على القالب: لا `replace` متتاليتان، فلا يُفسَّر `##` أو `%%`
     * الموجودان داخل الاسم الأصلي. الامتداد الأخير فقط (بعد آخر نقطة غير الأولى) هو الامتداد.
     */
    internal fun buildOne(input: RenameInput, spec: BatchRenameSpec, index: Int): Triple<String, String, String> {
        val full = input.name
        val dot = if (input.isDirectory) -1 else full.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val base = if (dot < 0) full else full.substring(0, dot)
        val originalExt = if (dot < 0) "" else full.substring(dot)
        val global = normalizeExtension(spec.extension)
        val ext = if (input.isDirectory || global.isEmpty()) originalExt else ".$global"
        val number = numberText(spec, index)
        val name = TOKEN.replace(spec.template) { m -> if (m.value == "%%") base else number } + ext
        return Triple(name, number, ext)
    }

    /**
     * المحرك الوحيد للمعاينة والتنفيذ. [inputs] بترتيب العرض الحالي (يُوزَّع الترقيم عليه بلا إعادة ترتيب).
     * [existingNames]: أسماء كل عناصر المجلد (بما فيها المحددة)؛ التعارض يُفحص مع غير المحددة فقط
     * لأن التنفيذ على مرحلتين يسمح بتبديل أسماء المحددة. المقارنة غير حساسة للحالة (وحدات أندرويد المشتركة كذلك).
     */
    fun plan(
        inputs: List<RenameInput>,
        spec: BatchRenameSpec,
        existingNames: Collection<String> = emptyList(),
        strictFat: Boolean = true
    ): RenamePlan {
        if (inputs.isEmpty()) return RenamePlan(emptyList(), RenameSpecError.EMPTY_SELECTION)
        if (spec.start < 0) return RenamePlan(emptyList(), RenameSpecError.BAD_START)
        if (spec.zeros !in 0..MAX_ZEROS) return RenamePlan(emptyList(), RenameSpecError.BAD_ZEROS)

        val own = inputs.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
        val outside = HashSet<String>().also { set ->
            existingNames.mapTo(set) { it.lowercase(Locale.ROOT) }
            set.removeAll(own)
        }
        val built = inputs.mapIndexed { i, input -> buildOne(input, spec, i) }
        val counts = HashMap<String, Int>()
        built.forEach { (name, _, _) -> counts[name.lowercase(Locale.ROOT)] = (counts[name.lowercase(Locale.ROOT)] ?: 0) + 1 }

        val rows = built.mapIndexed { i, (name, number, ext) ->
            val original = inputs[i].name
            val problem = NameRules.problem(name, strictFat)
            val key = name.lowercase(Locale.ROOT)
            val status = when {
                problem != null -> RenameStatus.INVALID
                (counts[key] ?: 0) > 1 -> RenameStatus.DUPLICATE
                key in outside -> RenameStatus.CONFLICT
                name == original -> RenameStatus.UNCHANGED
                else -> RenameStatus.OK
            }
            RenameRow(i, original, name, number, ext, status, problem)
        }
        return RenamePlan(rows)
    }
}

/** خطوة تسمية واحدة: الاسم القديم ← اسم مؤقت ← الاسم النهائي. */
data class RenameStep(val from: Path, val temp: Path, val to: Path)

enum class RenamePhase { TO_TEMP, TO_FINAL }

/** يُستدعى بعد كل نقلة ناجحة ليُسجَّل التقدم (سجل Room لاحقاً) فتُستردّ العملية إن انقطعت. */
fun interface RenameJournal {
    fun record(phase: RenamePhase, step: RenameStep)
}

sealed interface RenameOutcome {
    data class Success(val steps: List<RenameStep>) : RenameOutcome
    data class Failure(val cause: Exception, val rolledBack: Boolean) : RenameOutcome
}

object RenameEngine {
    /** يبني الخطوات من أزواج (قديم، جديد) داخل مجلد واحد، ويتجاهل ما لم يتغير اسمه. */
    fun plan(
        dir: Path,
        renames: List<Pair<String, String>>,
        opId: String = UUID.randomUUID().toString().take(8)
    ): List<RenameStep> =
        renames.filter { it.first != it.second }
            .mapIndexed { i, (old, new) ->
                RenameStep(dir.resolve(old), dir.resolve(".fm-ren-$opId-$i"), dir.resolve(new))
            }

    /**
     * التنفيذ على مرحلتين: كل الملفات إلى أسماء مؤقتة، ثم إلى النهائية،
     * فيعمل التبديل المتسلسل (A→B و B→C) بلا تعارض. عند أي فشل يُتراجع عمّا تم.
     */
    fun execute(steps: List<RenameStep>, journal: RenameJournal? = null): RenameOutcome {
        validatePlan(steps)?.let { return RenameOutcome.Failure(it, rolledBack = true) }
        val movedToTemp = ArrayList<RenameStep>()
        val movedToFinal = ArrayList<RenameStep>()
        return try {
            for (s in steps) {
                if (Files.exists(s.temp, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(s.temp.toString())
                Files.move(s.from, s.temp)
                movedToTemp += s
                journal?.record(RenamePhase.TO_TEMP, s)
            }
            for (s in steps) {
                if (Files.exists(s.to, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(s.to.toString())
                Files.move(s.temp, s.to, StandardCopyOption.ATOMIC_MOVE)
                movedToFinal += s
                journal?.record(RenamePhase.TO_FINAL, s)
            }
            RenameOutcome.Success(steps)
        } catch (e: Exception) {
            RenameOutcome.Failure(e, rollback(movedToTemp, movedToFinal))
        }
    }

    /**
     * يرفض خطة غير متجانسة أو متصادمة قبل أول إعادة تسمية. يُسمح أن تكون
     * الوجهة اسم مصدر آخر في الدفعة (التبديل/السلسلة)، لكن لا يُسمح بوجهة
     * محجوزة خارج الدفعة أو باسم مؤقت مستخدم في أي خطوة.
     */
    private fun validatePlan(steps: List<RenameStep>): Exception? {
        if (steps.isEmpty()) return null
        val normalized = steps.map { s ->
            Triple(s.from.toAbsolutePath().normalize(), s.temp.toAbsolutePath().normalize(), s.to.toAbsolutePath().normalize())
        }
        val fileSystem = normalized.first().first.fileSystem
        val parent = normalized.first().first.parent ?: return IOException("rename source has no parent")
        if (normalized.any { (from, temp, to) ->
                from.fileSystem != fileSystem || temp.fileSystem != fileSystem || to.fileSystem != fileSystem ||
                    from.parent != parent || temp.parent != parent || to.parent != parent
            }
        ) return IOException("all rename paths must share one directory and filesystem")

        val sources = normalized.map { it.first }
        val temps = normalized.map { it.second }
        val targets = normalized.map { it.third }
        if (sources.toSet().size != sources.size) return IOException("duplicate rename source")
        if (temps.toSet().size != temps.size) return IOException("duplicate temporary rename path")
        if (targets.toSet().size != targets.size) return IOException("duplicate final rename target")
        if (temps.any { it in sources || it in targets }) return IOException("temporary path overlaps a source or final target")
        if (sources.any { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }) return IOException("rename source does not exist")
        if (temps.any { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }) return IOException("temporary rename path already exists")
        val sourceSet = sources.toSet()
        if (targets.any { Files.exists(it, LinkOption.NOFOLLOW_LINKS) && it !in sourceSet }) {
            return FileAlreadyExistsException("rename target is occupied outside this batch")
        }
        return null
    }

    /** خطوات التراجع بعد نجاح التنفيذ (زر «تراجع»). تُنفَّذ بـ [execute] نفسها. */
    fun undoSteps(done: List<RenameStep>): List<RenameStep> =
        done.map { RenameStep(from = it.to, temp = it.temp, to = it.from) }

    /**
     * التراجع يمر عبر الأسماء المؤقتة دائماً (النهائي ← المؤقت ← الأصلي) لأن
     * الاسم الأصلي لخطوة قد يكون محجوزاً باسم نهائي لخطوة أخرى في سلسلة A→B→C.
     */
    private fun rollback(toTemp: List<RenameStep>, toFinal: List<RenameStep>): Boolean {
        var ok = true
        for (s in toFinal.asReversed()) ok = tryMove(s.to, s.temp) && ok
        for (s in toTemp.asReversed()) ok = tryMove(s.temp, s.from) && ok
        return ok
    }

    private fun tryMove(from: Path, to: Path): Boolean =
        try {
            Files.move(from, to)
            true
        } catch (e: Exception) {
            false
        }
}
