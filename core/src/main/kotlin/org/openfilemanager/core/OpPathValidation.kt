package org.openfilemanager.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * فحص مسار الوجهة قبل إنشائه (مستخرج من [FileOpEngine] بلا تغيير سلوك):
 * رفض النسخ/النقل إلى داخل أي مصدر، مع معالجة الوجهة غير الموجودة
 * والمكونات غير المنشأة بعد والروابط الرمزية.
 */
internal object OpPathValidation {

    /** يرفض الوجهة داخل أي مصدر حتى لو كانت بعض مكونات الوجهة لم تُنشأ بعد. */
    internal fun ensureTargetOutsideSources(targetDir: Path, sources: List<Path>) {
        val realTarget = resolveRealPathAllowMissing(targetDir)
        for (source in sources) {
            val realSource = tryOrNull { source.toRealPath() } ?: continue
            if (realTarget == realSource || realTarget.startsWith(realSource)) {
                throw TargetInsideSourceException(CoreOpCode.message(CoreOpCode.DEST_INSIDE_SOURCE, source))
            }
        }
    }

    /** يحل الجزء الموجود فعلياً عبر toRealPath ثم يعيد إلحاق اللاحقة غير المنشأة. */
    internal fun resolveRealPathAllowMissing(path: Path): Path {
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
}
