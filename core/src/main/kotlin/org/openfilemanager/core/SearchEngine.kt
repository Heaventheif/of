package org.openfilemanager.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** نتيجة مطابقة واحدة: بيانات وصفية فقط والمسار يُبنى عند الحاجة. */
data class SearchHit(
    val name: String,
    /** المجلد الحاوي نصاً؛ المستدعي يبني المسار الكامل بـ resolve. */
    val dir: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long
)

data class SearchReport(
    val hits: List<SearchHit>,
    /** توقف المسح لبلوغ سقف النتائج. */
    val truncated: Boolean,
    /** توقف المسح بطلب إلغاء. */
    val cancelled: Boolean,
    /** عدد العناصر/المجلدات التي تعذر قراءتها وتجاوزها المسح. */
    val errors: Int
)

/** بحث تدفقي قابل للإلغاء: «مسح بالتدفق (walkFileTree) قابل للإلغاء، دون فهرسة دائمة» — لا يُبنى أي فهرس، والمسح يتوقف فوراً عند [isCancelled] أو عند. */
class SearchEngine(private val maxResults: Int = DEFAULT_MAX_RESULTS) {

    init {
        require(maxResults > 0) { "maxResults must be positive: $maxResults" }
    }

    fun search(
        root: Path,
        matches: (path: Path, attrs: BasicFileAttributes) -> Boolean,
        isCancelled: () -> Boolean = { false },
        /** false: المجلدات المخفية (تبدأ بنقطة) لا يُنزَل إليها أصلاً، فلا يتسرب محتواها للنتائج. */
        includeHidden: Boolean = true
    ): SearchReport {
        val hits = ArrayList<SearchHit>(64)
        var truncated = false
        var cancelled = false
        var errors = 0

        val visitor = object : SimpleFileVisitor<Path>() {
            private fun hit(path: Path, attrs: BasicFileAttributes) {
                val parentPath = path.parent
                hits += SearchHit(
                    name = path.fileName?.toString() ?: path.toString(),
                    dir = parentPath?.toString() ?: "",
                    isDirectory = attrs.isDirectory,
                    size = if (attrs.isDirectory) 0L else attrs.size(),
                    modifiedAt = attrs.lastModifiedTime().toMillis()
                )
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) {
                    cancelled = true
                    return FileVisitResult.TERMINATE
                }
                if (dir != root && !includeHidden && dir.fileName?.toString()?.startsWith(".") == true) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (dir != root && matches(dir, attrs)) {
                    hit(dir, attrs)
                    if (hits.size >= maxResults) {
                        truncated = true
                        return FileVisitResult.TERMINATE
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) {
                    cancelled = true
                    return FileVisitResult.TERMINATE
                }
                if (matches(file, attrs)) {
                    hit(file, attrs)
                    if (hits.size >= maxResults) {
                        truncated = true
                        return FileVisitResult.TERMINATE
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                errors++
                return FileVisitResult.CONTINUE
            }
        }

        try {
            Files.walkFileTree(root, visitor)
        } catch (_: IOException) {
            // A missing/unreadable root is not an empty successful search.
            errors++
        }
        return SearchReport(hits, truncated, cancelled, errors)
    }

    companion object {
        const val DEFAULT_MAX_RESULTS = 10_000

        /**
         * مطابقة «يحتوي الاسم» غير حساسة لحالة الحرف مع خيار
         * استبعاد المخفي. الاستدعاء يمرر includeHidden=showHidden من الإعدادات.
         */
        fun namePredicate(query: String, includeHidden: Boolean = true): (Path, BasicFileAttributes) -> Boolean {
            val needle = query.trim().lowercase(Locale.ROOT)
            return { path, _ ->
                val name = path.fileName?.toString() ?: ""
                (includeHidden || !name.startsWith(".")) &&
                    needle.isNotEmpty() &&
                    name.lowercase(Locale.ROOT).contains(needle)
            }
        }
    }
}
