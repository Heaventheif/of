package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Validates a user-provided single-component name and renames without overwriting. */
object SafeRename {
    @Throws(IOException::class, IllegalArgumentException::class)
    fun rename(source: Path, newName: String, strictFat: Boolean = true): Path {
        val name = newName.trim()
        val problem = NameRules.problem(name, strictFat)
        if (problem != null) throw IllegalArgumentException("invalid name: $problem")
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw IOException("source does not exist")
        val parent = source.toAbsolutePath().normalize().parent ?: throw IOException("source has no parent")
        val target = parent.resolve(name).normalize()
        if (target.parent != parent) throw IllegalArgumentException("name must be a single path component")
        if (target == source.toAbsolutePath().normalize()) return source
        if (isCaseOnlyChange(source, target)) renameCaseOnly(source, target) else AtomicIO.moveNoReplace(source, target)
        AtomicIO.fsyncDirectory(parent)
        return target
    }

    /**
     * على نظام ملفات غير حساس للحالة (FAT/exFAT) يبدو الهدف «موجوداً» لأنه المصدر نفسه؛
     * نتعرف على هذه الحالة فقط: المجلد نفسه، والاسمان يختلفان بالحالة وحدها، والملف واحد.
     */
    private fun isCaseOnlyChange(source: Path, target: Path): Boolean {
        val from = source.fileName?.toString() ?: return false
        val to = target.fileName?.toString() ?: return false
        if (from == to || !from.equals(to, ignoreCase = true)) return false
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.isSameFile(source, target)
        } catch (_: IOException) {
            false
        }
    }

    /** خطوتان عبر اسم مؤقت؛ عند فشل الثانية يُعاد الاسم الأصلي فلا يضيع الملف. */
    private fun renameCaseOnly(source: Path, target: Path) {
        val temp = AtomicIO.tempSibling(target)
        AtomicIO.moveNoReplace(source, temp)
        try {
            AtomicIO.moveNoReplace(temp, target)
        } catch (t: Throwable) {
            runCatching { AtomicIO.moveNoReplace(temp, source) }
            throw t
        }
    }
}
