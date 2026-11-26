package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Where the bytes of an entry come from when the archive is written back. */
sealed interface ZipSource {
    /** Still inside the archive on disk, under its original entry name. */
    data class Original(val name: String, val unixMode: Int = -1) : ZipSource

    /** A file outside the archive (added, or the edited replacement of an entry). */
    data class External(val path: Path) : ZipSource

    data object Directory : ZipSource
}

data class ZipEntryInfo(
    val name: String,
    val size: Long,
    val modifiedAt: Long,
    val source: ZipSource,
    val unixMode: Int = -1
) {
    val isDirectory: Boolean get() = name.endsWith("/")
    val baseName: String get() = name.trimEnd('/').substringAfterLast('/')

    /** Containing folder: "" for the root, otherwise "a/b/". */
    val parent: String
        get() = name.trimEnd('/').let { if (it.contains('/')) it.substringBeforeLast('/') + "/" else "" }
}

class ZipEditException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason { INVALID_NAME, EXISTS, MISSING, INTO_ITSELF, UNSUPPORTED }
}

/** Result of opening a zip for editing. [model] is null when editing is not safe; [reason] says why. */
class ZipLoad(val model: ZipModel?, val reason: String?) {
    val editable: Boolean get() = model != null
}

/** An in-memory, editable picture of a ZIP. */
class ZipModel internal constructor(val archive: Path, entries: LinkedHashMap<String, ZipEntryInfo>, private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private var map: LinkedHashMap<String, ZipEntryInfo> = entries

    @Volatile
    var dirty: Boolean = false
        private set

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000

        /** أقصى عمق شجرة يُقبل عند إضافة مجلد من خارج الأرشيف إلى النموذج. */
        private const val MAX_TREE_DEPTH = 100

        /**
         * Reads the central directory only. Refuses (editable = false) when anything could be lost or
         * be unsafe on rewrite: path tricks, duplicate or clashing names, or too many entries.
         */
        fun load(archive: Path, maxEntries: Int = DEFAULT_MAX_ENTRIES): ZipLoad {
            when (ZipCentralDirectoryCheck.check(archive, maxEntries)) {
                is ZipCentralDirectoryCheck.Verdict.OverLimit,
                is ZipCentralDirectoryCheck.Verdict.CentralDirectoryTooLarge,
                is ZipCentralDirectoryCheck.Verdict.Inconsistent ->
                    return ZipLoad(null, "too-many-entries")
                else -> {}
            }
            // builder بدل المُنشئ المهجور ZipFile(File)؛ الافتراضي UTF-8 كما كان.
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(archive).get().use { zf ->
                val map = LinkedHashMap<String, ZipEntryInfo>()
                var count = 0
                val e = zf.entries
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    if (++count > maxEntries) return ZipLoad(null, "too-many-entries")
                    val name = entry.name
                    if (!isSafeName(name)) return ZipLoad(null, "unsafe-name")
                    if (map.containsKey(name)) return ZipLoad(null, "duplicate-name")
                    val dir = name.endsWith("/")
                    map[name] = ZipEntryInfo(
                        name, if (dir) -1L else entry.size, entry.time.coerceAtLeast(0L),
                        if (dir) ZipSource.Directory else ZipSource.Original(name, unixModeOf(entry)),
                        unixMode = if (dir) unixModeOf(entry) else -1
                    )
                }
                // "x" and "x/" together would collapse on extraction.
                if (map.keys.any { !it.endsWith("/") && map.containsKey("$it/") }) return ZipLoad(null, "file-folder-clash")
                val missing = LinkedHashMap<String, ZipEntryInfo>()
                for (info in map.values) {
                    var parent = info.parent
                    while (parent.isNotEmpty() && !map.containsKey(parent) && !missing.containsKey(parent)) {
                        missing[parent] = ZipEntryInfo(parent, -1L, info.modifiedAt, ZipSource.Directory)
                        parent = ZipEntryInfo(parent, -1L, 0L, ZipSource.Directory).parent
                    }
                }
                map.putAll(missing)
                // التصادم يُفحص ثانيةً على الخريطة النهائية بعد بناء المجلدات الضمنية: ملف "a" مع مدخل "a/b.txt" بلا مجلد صريح "a/" كان ينشئ "a/" ضمنياً بعد الفحص…
                if (map.keys.any { !it.endsWith("/") && map.containsKey("$it/") }) {
                    return ZipLoad(null, "file-folder-clash")
                }
                return ZipLoad(ZipModel(archive, map), null)
            }
        }

        /** Single ZIP-entry name rule used by load, add, rename and save (backslash is rejected everywhere). */
        internal fun entryNameProblem(name: String): NameProblem? =
            if (name.contains('\\')) NameProblem.FORBIDDEN_CHAR else NameRules.problem(name, strictFat = false)

        internal fun isSafeName(name: String): Boolean {
            if (name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return false
            val parts = name.removeSuffix("/").split('/')
            return parts.none { it.isEmpty() || it == "." || it == ".." }
        }

        internal fun unixModeOf(entry: org.apache.commons.compress.archivers.zip.ZipArchiveEntry): Int {
            val mode = (entry.externalAttributes ushr 16).toInt() and 0xFFF
            return if (mode == 0) -1 else mode
        }
    }

    // ---------- reading ----------

    @Synchronized
    fun entries(): List<ZipEntryInfo> = map.values.toList()

    @Synchronized
    fun get(name: String): ZipEntryInfo? = map[name]

    /** Direct children of [prefix] ("" = root, "a/b/" = that folder). */
    @Synchronized
    fun children(prefix: String): List<ZipEntryInfo> = map.values.filter { e ->
        e.name != prefix && e.name.startsWith(prefix) && !e.name.substring(prefix.length).trimEnd('/').contains('/')
    }

    // ---------- edits ----------

    @Synchronized
    fun delete(names: Collection<String>) {
        for (n in names) {
            if (n.endsWith("/")) map.keys.removeAll { it.startsWith(n) } else map.remove(n)
        }
        dirty = true
    }

    /** Renames inside the same folder. Returns the new full name. */
    @Synchronized
    fun rename(name: String, newBase: String): String {
        val info = map[name] ?: throw ZipEditException(ZipEditException.Reason.MISSING, name)
        val clean = newBase.trim()
        if (entryNameProblem(clean) != null) {
            throw ZipEditException(ZipEditException.Reason.INVALID_NAME, clean)
        }
        val newName = info.parent + clean + if (info.isDirectory) "/" else ""
        if (newName == name) return name
        if (clashes(newName)) throw ZipEditException(ZipEditException.Reason.EXISTS, newName)
        remap(name, newName)
        return newName
    }

    /**
     * Renames several entries at once, each inside its own folder. [renames] = (full entry name, new base name).
     * All-or-nothing: the whole batch is validated and the new map is built aside, so a swap such as
     * 1→2 and 2→1 works and any failure leaves the model untouched. Returns how many entries changed.
     */
    @Synchronized
    fun renameBatch(renames: List<Pair<String, String>>): Int {
        val moves = LinkedHashMap<String, String>() // old full name -> new full name
        for ((name, newBase) in renames) {
            val info = map[name] ?: throw ZipEditException(ZipEditException.Reason.MISSING, name)
            // الاسم يُستعمل كما هو (بلا trim): المسافات التي كتبها المستخدم محفوظة.
            if (entryNameProblem(newBase) != null) {
                throw ZipEditException(ZipEditException.Reason.INVALID_NAME, newBase)
            }
            val newName = info.parent + newBase + if (info.isDirectory) "/" else ""
            if (newName != name) moves[name] = newName
        }
        if (moves.isEmpty()) return 0
        if (moves.values.toSet().size != moves.size) {
            throw ZipEditException(ZipEditException.Reason.EXISTS, "duplicate target in batch")
        }
        fun movedFrom(key: String): String? =
            moves.keys.firstOrNull { from -> key == from || (from.endsWith("/") && key.startsWith(from)) }

        val out = LinkedHashMap<String, ZipEntryInfo>(map.size)
        for ((key, value) in map) {
            val from = movedFrom(key)
            val newKey = if (from == null) key else moves.getValue(from) + key.removePrefix(from)
            if (out.containsKey(newKey)) throw ZipEditException(ZipEditException.Reason.EXISTS, newKey)
            out[newKey] = if (from == null) value else value.copy(name = newKey)
        }
        // "x" و"x/" معاً ينهاران عند الاستخراج.
        if (out.keys.any { !it.endsWith("/") && out.containsKey("$it/") }) {
            throw ZipEditException(ZipEditException.Reason.EXISTS, "file/folder clash")
        }
        map = out
        dirty = true
        return moves.size
    }

    /**
     * Moves or copies entries (and everything under folders) into [destFolder]. Name clashes get a
     * " (n)" suffix instead of overwriting. Returns the resulting names.
     */
    @Synchronized
    fun transfer(names: Collection<String>, destFolder: String, copy: Boolean): List<String> {
        if (destFolder.isNotEmpty() && map[destFolder]?.isDirectory != true) {
            throw ZipEditException(ZipEditException.Reason.MISSING, destFolder)
        }
        val top = names.filter { n -> names.none { o -> o != n && o.endsWith("/") && n.startsWith(o) } }
        val result = ArrayList<String>(top.size)
        for (n in top) {
            val info = map[n] ?: throw ZipEditException(ZipEditException.Reason.MISSING, n)
            if (info.isDirectory && destFolder.startsWith(n)) {
                throw ZipEditException(ZipEditException.Reason.INTO_ITSELF, n)
            }
            if (!copy && info.parent == destFolder) { result += n; continue }
            val target = uniqueName(destFolder, info.baseName, info.isDirectory)
            if (copy) duplicate(n, target) else remap(n, target)
            result += target
        }
        dirty = true
        return result
    }

    @Synchronized
    fun mkdir(parent: String, name: String): String {
        val clean = name.trim()
        if (entryNameProblem(clean) != null) {
            throw ZipEditException(ZipEditException.Reason.INVALID_NAME, clean)
        }
        if (parent.isNotEmpty() && map[parent]?.isDirectory != true) {
            throw ZipEditException(ZipEditException.Reason.MISSING, parent)
        }
        val created = "$parent$clean/"
        if (clashes(created)) throw ZipEditException(ZipEditException.Reason.EXISTS, created)
        map[created] = ZipEntryInfo(created, -1L, System.currentTimeMillis(), ZipSource.Directory)
        dirty = true
        return created
    }

    /** Replaces the content of an existing file entry with an edited file from outside. */
    @Synchronized
    fun replaceFile(entry: String, file: Path) {
        val info = map[entry]?.takeIf { !it.isDirectory } ?: throw ZipEditException(ZipEditException.Reason.MISSING, entry)
        map[entry] = info.copy(
            size = Files.size(file), modifiedAt = Files.getLastModifiedTime(file).toMillis(), source = ZipSource.External(file)
        )
        dirty = true
    }

    /** Adds a file, or a whole folder tree, from outside into [folder]. Returns the top-level name added.
     *  الشجرة تُجمع كلها قبل أي تعديل على النموذج، بعدّة متكررة بمكدس صريح
     *  وبسقوف عمق وعدد — تجاوزه يرمي قبل أن يتغير النموذج بشيء. */
    @Synchronized
    fun add(folder: String, path: Path): String {
        if (folder.isNotEmpty() && map[folder]?.isDirectory != true) throw ZipEditException(ZipEditException.Reason.MISSING, folder)
        if (Files.isSymbolicLink(path)) throw ZipEditException(ZipEditException.Reason.UNSUPPORTED, path.toString())
        val base = path.fileName.toString()
        if (entryNameProblem(base) != null) throw ZipEditException(ZipEditException.Reason.INVALID_NAME, base)
        val isDir = Files.isDirectory(path)
        val top = uniqueName(folder, base, isDir)
        if (isDir) {
            val collected = collectTree(top, path, maxEntries - map.size)
            if (map.size + collected.size > maxEntries) {
                throw ZipEditException(ZipEditException.Reason.UNSUPPORTED, "too many entries")
            }
            for (c in collected) {
                map[c.name] = if (c.isDirectory) {
                    ZipEntryInfo(c.name, -1L, c.modifiedAt, ZipSource.Directory, unixMode = c.unixMode)
                } else {
                    ZipEntryInfo(c.name, c.size, c.modifiedAt, ZipSource.External(c.path!!), unixMode = c.unixMode)
                }
            }
        } else {
            map[top] = fileInfo(top, path)
        }
        dirty = true
        return top
    }

    // ---------- helpers ----------

    /** عنصر شجرة مجمّع قبل إضافته إلى النموذج : جمع كامل ثم إلزام، فلا نموذج ناقص عند الفشل. */
    private class CollectedEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long,
        val unixMode: Int,
        val path: Path?
    )

    /** يجمع شجرة المجلد المراد إضافتها. */
    private fun collectTree(top: String, root: Path, limit: Int): List<CollectedEntry> {
        val out = ArrayList<CollectedEntry>()
        val stack = ArrayDeque<Pair<Path, Pair<String, Int>>>() // (المسار الفعلي، بادئة الاسم داخل الأرشيف + العمق)
        stack.addLast(root to (top to 0))
        while (stack.isNotEmpty()) {
            val (dir, prefixAndDepth) = stack.removeLast()
            val (prefix, depth) = prefixAndDepth
            if (depth > MAX_TREE_DEPTH) {
                throw ZipEditException(ZipEditException.Reason.UNSUPPORTED, "tree too deep")
            }
            val children = ArrayList<Path>(64)
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    if (Files.isSymbolicLink(child)) continue // never follow links into an archive
                    if (entryNameProblem(child.fileName.toString()) != null) continue
                    // add() صريحة لا «+=»: K2 لا يحلّها على plusAssign مع عنصر بنمط منصة (Path!).
                    children.add(child)
                }
            }
            for (child in children) {
                // Enforce the entry cap while collecting, before the whole tree is materialized
                if (out.size >= limit) throw ZipEditException(ZipEditException.Reason.UNSUPPORTED, "too many entries")
                val base = child.fileName.toString()
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    val name = "$prefix$base/"
                    out += CollectedEntry(name, true, -1L, lastModifiedOf(child), -1, null)
                    stack.addLast(child to (name to depth + 1))
                } else {
                    out += CollectedEntry("$prefix$base", false, runCatching { Files.size(child) }.getOrDefault(0L), lastModifiedOf(child), posixModeOf(child), child)
                }
            }
        }
        return out
    }

    private fun lastModifiedOf(p: Path): Long =
        runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)

    private fun posixModeOf(file: Path): Int = runCatching {
        val attrs = Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes::class.java)
        attrs.permissions().fold(0) { acc, p -> acc or p.toMask() }
    }.getOrDefault(-1)

    private fun fileInfo(name: String, file: Path): ZipEntryInfo {
        val mode = runCatching {
            val attrs = Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes::class.java)
            attrs.permissions().fold(0) { acc, p -> acc or p.toMask() }
        }.getOrDefault(-1)
        return ZipEntryInfo(
            name, Files.size(file), Files.getLastModifiedTime(file).toMillis(),
            ZipSource.External(file), unixMode = mode
        )
    }

    /** بت صلاحية POSIX واحدة إلى قناعه الرقمي (rwx)؛ قيم عشرية لأن 0oXXX غير مدعومة في هذه النسخة. */
    private fun java.nio.file.attribute.PosixFilePermission.toMask(): Int = when (this) {
        java.nio.file.attribute.PosixFilePermission.OWNER_READ -> 256    // 0o400
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE -> 128   // 0o200
        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE -> 64  // 0o100
        java.nio.file.attribute.PosixFilePermission.GROUP_READ -> 32     // 0o040
        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE -> 16     // 0o020
        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE -> 8    // 0o010
        java.nio.file.attribute.PosixFilePermission.OTHERS_READ -> 4      // 0o004
        java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE -> 2     // 0o002
        java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE -> 1   // 0o001
    }

    private fun clashes(name: String): Boolean {
        val other = if (name.endsWith("/")) name.dropLast(1) else "$name/"
        return map.containsKey(name) || map.containsKey(other)
    }

    private fun uniqueName(folder: String, base: String, isDir: Boolean): String {
        val suffix = if (isDir) "/" else ""
        // توحيد منطق الاسم الفريد عبر NameRules.uniqueName — كان مكرراً.
        val unique = NameRules.uniqueName(base, clashes = { c -> clashes("$folder$c$suffix") }, stripExtension = !isDir)
        return "$folder$unique$suffix"
    }

    private fun remap(from: String, to: String) {
        val out = LinkedHashMap<String, ZipEntryInfo>(map.size)
        for ((key, value) in map) {
            if (key == from || (from.endsWith("/") && key.startsWith(from))) {
                val renamed = to + key.removePrefix(from)
                out[renamed] = value.copy(name = renamed)
            } else {
                out[key] = value
            }
        }
        map = out
        dirty = true
    }

    private fun duplicate(from: String, to: String) {
        val copies = map.values.filter { it.name == from || (from.endsWith("/") && it.name.startsWith(from)) }
            .map { it.copy(name = to + it.name.removePrefix(from)) }
        copies.forEach { map[it.name] = it }
        dirty = true
    }
}
