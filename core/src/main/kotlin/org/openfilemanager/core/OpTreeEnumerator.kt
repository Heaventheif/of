package org.openfilemanager.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** تعداد أشجار المصادر لتخطيط الدفعة. */
internal class OpTreeEnumerator(
    private val maxEnumeratedItems: Int,
    private val sameVolume: (Path, Path) -> Boolean,
    private val cancelCheck: () -> Unit
) {
    internal fun enumerate(opId: String, kind: OpKind, sources: List<Path>, targetDir: Path, lazySizes: Boolean): List<OpItemRecord> {
        val items = ArrayList<OpItemRecord>()
        var index = 0
        fun checkItemsCap() {
            // سقف التعداد: فشل صريح برسالة واضحة بدل OOM صامت على أشجار ضخمة.
            if (items.size > maxEnumeratedItems) {
                throw IOException("enumeration exceeded $maxEnumeratedItems items; operation refused")
            }
        }
        checkItemsCap()
        sources.forEachIndexed { group, source ->
            val exists = Files.exists(source, LinkOption.NOFOLLOW_LINKS)
            val isDir = exists && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
            when {
                // Root symlinks are unlike nested symlinks: the latter are already skipped by enumerateTree.
                exists && Files.isSymbolicLink(source) && kind != OpKind.DELETE &&
                    !(kind == OpKind.MOVE && sameVolume(source, targetDir)) -> {
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.FILE, state = ItemState.SKIPPED,
                        src = source.toString(), dst = targetDir.resolve(source.fileName).toString(),
                        extra = source.fileName.toString(), error = CoreOpCode.SYMLINK
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

    internal fun enumerateTree(
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
                cancelCheck()
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
                cancelCheck()
                checkItemsCap()
                val rel = sourceRoot.relativize(file).toString()
                if (attrs.isSymbolicLink) {
                    items += OpItemRecord(
                        opId = opId, index = index++, group = group,
                        itemKind = OpItemKind.FILE, state = ItemState.SKIPPED,
                        src = file.toString(), dst = dstRoot.resolve(rel).toString(),
                        extra = rel, error = CoreOpCode.SYMLINK
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
                cancelCheck()
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

    internal fun treeSize(root: Path): Long {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return fileSize(root)
        var total = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                cancelCheck()
                if (!attrs.isSymbolicLink) total += attrs.size()
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    internal fun fileSize(file: Path): Long =
        tryOrNull { Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size() } ?: 0
}
