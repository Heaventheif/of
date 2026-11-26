package org.openfilemanager.app.usecase

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.openfilemanager.core.ZipEditException
import org.openfilemanager.core.ZipLoad
import org.openfilemanager.core.ZipModel
import org.openfilemanager.core.ZipRewriter
import org.openfilemanager.core.ZipSource

class ZipItemTransfer(
    /** سقف عدد المداخل عند فتح أرشيف من القرص (خطوة النقل الخارجية) كما هو اليوم. */
    private val maxEntries: () -> Int = { ZipModel.DEFAULT_MAX_ENTRIES },
    /** فتح أرشيف من القرص للتحرير — الافتراضي نداء ZipModel.load نفسه بالسقف نفسه. */
    private val loadModel: (Path) -> ZipLoad = { ZipModel.load(it, maxEntries()) },
    /** كتابة النموذج على القرص — الافتراضي ZipRewriter.write كما هو. */
    private val write: (ZipModel) -> Unit = { ZipRewriter.write(it) },
    /** تحويل مسار نصي من الحافظة إلى مسار فعلي: نفس تحويل اليوم File(it).toPath(). */
    private val pathOf: (String) -> Path = { File(it).toPath() },
) {

    /** العملية المقررة على النموذج بعد قرار اللصق (قرار بلا تنفيذ). */
    sealed interface PasteOp {
        /** لصق من الأرشيف نفسه: نقل/نسخ مداخل إلى مجلد داخلي (كان: it.transfer(...)). */
        data class Transfer(val entries: List<String>, val into: String, val copy: Boolean) : PasteOp

        /** لصق من القرص: إضافة كل مسار إلى مجلد داخلي (كان: m.add(loc.inner, ...)). */
        data class AddFromDisk(val paths: List<String>, val into: String) : PasteOp
    }

    /** قرار «الصق داخل الأرشيف المفتوح» — كله بلا آثار جانبية ولا I/O. */
    sealed interface PasteDecision {
        /** لا نموذج تحرير: الواجهة تعرض ui_err_readonly_archive وتعود دون مسح الحافظة. */
        data object NoEditableModel : PasteDecision

        /** الحافظة من أرشيف آخر: الواجهة تعرض ui_err_other_archive وتعود دون مسح الحافظة. */
        data object FromOtherArchive : PasteDecision

        /**
         * نفّذ العملية على النموذج ثم امسح الحافظة (كما كان التسلسل السابق:
         * editArchive تُطلق الكتلة، تسجيل حذف الأصول متزامن فور ذلك حتى لو
         * فشلت الكتلة لاحقاً، ثم مسح الحافظة).
         */
        data class Paste(
            val op: PasteOp,
            /** نقل من القرص: أصول الحافظة تُحذف من القرص بعد نجاح الحفظ فقط. */
            val pendingSourceDeletes: Boolean,
        ) : PasteDecision
    }

    /**
     * قرار اللصق — الحرف من [BrowserViewModel.pasteIntoArchive] السابقة:
     * فحص النموذج أولاً ثم مصدر الحافظة (أرشيف آخر؟ الأرشيف نفسه؟ القرص؟).
     */
    fun planPaste(
        model: ZipModel?,
        currentArchive: File,
        sourceArchive: String?,
        paths: List<String>,
        into: String,
        move: Boolean,
    ): PasteDecision {
        if (model == null) return PasteDecision.NoEditableModel
        if (sourceArchive != null) {
            if (File(sourceArchive) != currentArchive) return PasteDecision.FromOtherArchive
            return PasteDecision.Paste(PasteOp.Transfer(paths, into, copy = !move), pendingSourceDeletes = false)
        }
        return PasteDecision.Paste(PasteOp.AddFromDisk(paths, into), pendingSourceDeletes = move)
    }

    /**
     * تنفيذ العملية المقررة على النموذج — حرفياً كما كانت كتلتا editArchive
     * السابقتان. يرمي [ZipEditException] كما هو (الواجهة تحوّل السبب إلى رسالة).
     * متزامن بلا I/O شبكي: add يقرأ حجم الملف المضاف فقط كما اليوم.
     */
    fun execute(op: PasteOp, model: ZipModel) {
        when (op) {
            is PasteOp.Transfer -> model.transfer(op.entries, op.into, copy = op.copy)
            is PasteOp.AddFromDisk -> op.paths.forEach { model.add(op.into, pathOf(it)) }
        }
    }

    /** نتيجة خطوة «حذف المنقول من ملف الأرشيف»: نجحت أو فشلت بالخطأ كما هو. */
    sealed interface MoveOutResult {
        data object Deleted : MoveOutResult
        data class Failed(val error: Throwable) : MoveOutResult
    }

    /** خطوة «النقل» من أرشيف إلى مجلد على القرص. */
    fun deleteMovedEntries(archive: Path, entries: List<String>): MoveOutResult {
        return try {
            val m = loadModel(archive).model
                ?: throw ZipEditException(ZipEditException.Reason.UNSUPPORTED, "read-only archive")
            m.delete(entries)
            write(m)
            MoveOutResult.Deleted
        } catch (t: Throwable) {
            MoveOutResult.Failed(t)
        }
    }

    /** نسخ مدخل واحد من النموذج إلى [destFolder] لعرضه. */
    fun extractEntryForView(model: ZipModel, entry: String, displayName: String, destFolder: File): File? {
        val target = File(destFolder, displayName)
        when (val src = model.get(entry)?.source) {
            is ZipSource.Original -> ZipRewriter.extractOne(model.archive, src.name, target.toPath())
            is ZipSource.External -> Files.copy(src.path, target.toPath())
            else -> return null
        }
        return target
    }
}
