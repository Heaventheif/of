package org.openfilemanager.core

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

class VerificationFailedException(message: String) : IOException(message)

class CopyResult(val bytes: Long, val sha256: ByteArray)

/**
 * عمليات الملفات الذرية. لا يظهر ملف نصف مكتمل باسم نهائي أبداً:
 * الكتابة إلى ملف مؤقت ثم force ثم نقل ذري.
 */
object AtomicIO {
    private const val BUFFER_SIZE = 1 shl 20
    private const val MIN_BUFFER_SIZE = 8 shl 10
    private const val TRANSFER_CHUNK = 8L shl 20

    internal fun tempSibling(target: Path): Path = target.resolveSibling(".fm-tmp-" + UUID.randomUUID())

    /**
     * مزامنة مجلد (fsync) بعد إعادة تسمية أو حذف فيه. بيانات الملف قد تكون
     * مكتوبة على الوسيط عبر force(true)، لكن rename(2)/unlink(2) بلا fsync
     * للمجلد قد يضيعان بعد انقطاع الطاقة (ext4/f2fs) — فلا ذرية بلا دوام.
     * أفضل جهد: ما لا يسمح بفتح مجلد قناةً (Jimfs في الاختبارات، بعض مزودات
     * FUSE/MediaProvider) يتراجع بصمت إلى السلوك السابق ولا يُفشل العملية.
     */
    internal fun fsyncDirectory(dir: Path?) {
        if (dir == null) return
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // لا فتح للمجلد أو مزامنة غير مدعومة على نظام الملفات هذا.
        } catch (_: UnsupportedOperationException) {
            // القناة لا تدعم force هنا.
        } catch (_: SecurityException) {
            // وصول مقيد للمجلد؛ لا أكثر مما قبلها.
        }
    }

    /**
     * مزامنة محتوى ملف كُتب للتو (fsync) قبل نشره بإعادة تسمية. إعادة التسمية الذرية
     * تحمي الاسم لا البيانات: بلا force قد يظهر الاسم النهائي بمحتوى ناقص بعد انقطاع الطاقة.
     * أفضل جهد كـ [fsyncDirectory]: ما لا يسمح بفتح القناة يتراجع بصمت.
     */
    internal fun fsyncFile(file: Path) {
        try {
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
        } catch (_: IOException) {
        } catch (_: UnsupportedOperationException) {
        } catch (_: SecurityException) {
        }
    }

    /** يزامن كل ملف ثم كل مجلد تحت [root] (الأعمق أولاً)، ثم [root] نفسه، قبل نشر الشجرة. */
    internal fun fsyncTree(root: Path) {
        val dirs = ArrayList<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) fsyncFile(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                dirs.add(dir)
                return FileVisitResult.CONTINUE
            }
        })
        dirs.forEach { fsyncDirectory(it) }
    }

    /** حجم المخزن بحجم الملف: ملف صغير لا يحجز ميغابايتاً كاملة (3.5)، بحد
     *  أدنى 8 كيلوبايت وبحد أقصى سقف المخزن — السلوك نفسه للملفات الكبيرة. */
    private fun bufferFor(fileSize: Long): Int =
        fileSize.coerceAtMost(BUFFER_SIZE.toLong()).toInt().coerceAtLeast(MIN_BUFFER_SIZE)

    /** كتابة آمنة بمرحلتين، تستبدل الهدف الموجود ذرياً. الاسم المؤقت ثابت الطول فلا يتجاوز حد 255 بايت. */
    fun atomicWrite(target: Path, write: (OutputStream) -> Unit) {
        val tmp = tempSibling(target)
        try {
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            ).use { ch ->
                write(Channels.newOutputStream(ch))
                ch.force(true)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // FAT/exFAT وبعض مزودات FUSE: المؤقت كامل ومُزامَن، فالاستبدال العادي آمن كبديل.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            fsyncDirectory(target.parent)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }

    /**
     * نسخ ملف إلى ملف مؤقت في مجلد الوجهة ثم نقل ذري إلى الاسم النهائي.
     * [hash] = true يحسب SHA-256 أثناء النسخ (لازم للنقل بين وحدتين) وهذا يمنع استخدام transferTo
     * لأنه لا يمرّ بالبيانات عبر الذاكرة. بدونه تُستخدم دفعات transferTo بحجم 8 ميغابايت.
     */
    fun copyAtomic(
        src: Path,
        dst: Path,
        replace: Boolean = false,
        hash: Boolean = false,
        onProgress: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
        preserveModifiedTime: Boolean = false
    ): CopyResult {
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.NoSuchFileException(src.toString())
        if (Files.isSymbolicLink(src)) throw IOException("symbolic-link source is not accepted: $src")
        if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) throw IOException("copyAtomic accepts files only: $src")
        if (!replace && Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(dst.toString())
        val tmp = tempSibling(dst)
        try {
            val digest = if (hash) MessageDigest.getInstance("SHA-256") else null
            var copied = 0L
            FileChannel.open(src, StandardOpenOption.READ).use { input ->
                FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    if (digest != null) {
                        val buf = ByteBuffer.allocate(bufferFor(input.size()))
                        while (true) {
                            checkCancelled()
                            buf.clear()
                            val n = input.read(buf)
                            if (n < 0) break
                            buf.flip()
                            digest.update(buf.duplicate())
                            while (buf.hasRemaining()) output.write(buf)
                            copied += n
                            onProgress(copied)
                        }
                    } else {
                        val size = input.size()
                        var pos = 0L
                        while (pos < size) {
                            checkCancelled()
                            val n = input.transferTo(pos, minOf(TRANSFER_CHUNK, size - pos), output)
                            if (n <= 0L) throw IOException("copy stalled at $pos of $size")
                            pos += n
                            copied = pos
                            onProgress(copied)
                        }
                    }
                    output.force(true)
                }
            }
            if (preserveModifiedTime) copyModifiedTime(src, tmp)
            // لا توجد دورة قراءة إضافية بعد آخر دفعة في مسار transferTo؛ افحص
            // الإلغاء عند نقطة الالتزام نفسها حتى لا ننشر الوجهة بعد طلب إلغاء
            // وصل مع onProgress للدفعة الأخيرة (بما في ذلك الملف الفارغ).
            checkCancelled()
            if (replace) {
                // (F-08) fallback متدرج بدل فشل كامل على أنظمة لا تدعم ATOMIC_MOVE.
                moveAtomicOrFallback(tmp, dst, replace = true)
            } else {
                moveNoReplace(tmp, dst)
            }
            // دوام إعادة التسمية قبل إعلان العنصر منتهياً (انظر fsyncDirectory).
            fsyncDirectory(dst.parent)
            return CopyResult(copied, digest?.digest() ?: ByteArray(0))
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }

    /**
     * نسخ وقت التعديل من المصدر إلى الملف المؤقت (قبل النقل الذري النهائي).
     * محاولة بأفضل جهد: بعض أنظمة الملفات (FAT/exFAT على بطاقات SD) قد ترفض
     * التعديل أو تقرّب الدقة؛ فشلها لا يُفشل النقل لأن البيانات صحيحة.
     */
    private fun copyModifiedTime(src: Path, target: Path) {
        try {
            val modified = Files.getLastModifiedTime(src, LinkOption.NOFOLLOW_LINKS)
            Files.setLastModifiedTime(target, modified)
        } catch (_: IOException) {
            // أفضل جهد فقط، انظر التوثيق أعلاه.
        } catch (_: UnsupportedOperationException) {
            // نظام الملفات لا يدعم تعديل الأوقات.
        }
    }

    /**
     * (F-08) نقل ذري عندما تدعمه وحدة التخزين، ونقل عادي عند رفض المزود
     * (AtomicMoveNotSupportedException: FAT/exFAT وبعض مزودات FUSE/MediaProvider).
     * [replace] = true يستبدل هدفاً موجوداً؛ false يفشل بالعقد المعتاد
     * (FileAlreadyExistsException) إن ظهر هدف — لا استبدال صامت في المسارين.
     * داخلي لاختبار بروتوكول الاستبدال بمحاكاة عدم دعم النقل الذري.
     */
    internal fun moveAtomicOrFallback(src: Path, dst: Path, replace: Boolean) {
        try {
            val options = if (replace) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            Files.move(src, dst, *options)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (replace) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            } else {
                moveNoReplace(src, dst)
            }
        }
    }

    /**
     * نقل إلى اسم غير موجود دون استبدال. لا نمرر ATOMIC_MOVE هنا لأن مزود
     * نظام الملفات قد يستبدل هدفاً ظهر في نافذة السباق عند استخدام هذا الخيار؛
     * النقل الافتراضي يحافظ على عقد FileAlreadyExistsException للملفات والمجلدات.
     */
    fun moveNoReplace(src: Path, dst: Path) {
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(dst.toString())
        Files.move(src, dst)
        fsyncDirectory(src.parent)
        if (dst.parent != src.parent) fsyncDirectory(dst.parent)
    }

    fun sha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buf = ByteArray(bufferFor(Files.size(path)))
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }

    /**
     * نقل ملف واحد. داخل الوحدة نفسها: إعادة تسمية ذرية فورية.
     * بين وحدتين: نسخ مع هاش ← إعادة قراءة الوجهة ومقارنة الهاش ← حذف المصدر عند التطابق فقط.
     * عند أي فشل أو عدم تطابق يبقى المصدر سليماً وتُحذف الوجهة الناقصة.
     *
     * الترتيب هنا يمس سلامة البيانات بعد انقطاع الطاقة: fsync مجلد الوجهة
     * (fsyncDirectory) يجعل rename(2) دائماً على الوسيط قبل unlink(2) للمصدر،
     * فلا يمكن أن يضيع الملف بين الخطوتين.
     *
     * Limit: the verification re-read may be served from the page cache (see
     * [dropPageCache]); the source is kept until the comparison succeeds.
     */
    fun moveVerified(
        src: Path,
        dst: Path,
        sameVolume: Boolean,
        replace: Boolean = false,
        onProgress: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
        dropPageCache: (Path) -> Unit = {}
    ) {
        checkCancelled()
        if (!replace && Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(dst.toString())
        if (sameVolume) {
            // Cancellation must be checked immediately before the rename commit point.
            checkCancelled()
            if (replace) {
                // (F-08) fallback متدرج على وسائط لا تدعم ATOMIC_MOVE (FAT/exFAT).
                moveAtomicOrFallback(src, dst, replace = true)
            } else {
                moveNoReplace(src, dst)
            }
            return
        }
        val staged = tempSibling(dst)
        try {
            val result = copyAtomic(
                src, staged, hash = true, onProgress = onProgress,
                checkCancelled = checkCancelled, preserveModifiedTime = true
            )
            dropPageCache(staged)
            if (!sha256(staged).contentEquals(result.sha256)) {
                throw VerificationFailedException("destination hash differs from source: $dst")
            }
            // A cancellation that arrived after the last copy chunk must not commit the
            // staged file. Keep the source and discard the staging file instead.
            checkCancelled()
            if (replace) {
                // (F-08) fallback متدرج بدل فشل النشر على وسائط بلا نقل ذري.
                moveAtomicOrFallback(staged, dst, replace = true)
            } else {
                moveNoReplace(staged, dst)
            }
            // نافذة فقدان البيانات كانت هنا: حذف المصدر قبل دوام إعادة التسمية
            // على الوسيط (انقطاع بعد الحذف وقبل دوام rename كان يفقد الملف كلياً).
            // الآن: fsync مجلد الوجهة أولاً، ولا يمس المصدر إلا بعدها.
            fsyncDirectory(dst.parent)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(staged) }
            throw t
        }
        // If cancellation arrives after publishing the destination, leave both copies
        // rather than risk losing the only copy. A later retry can reconcile them.
        checkCancelled()
        Files.delete(src)
        // وحذف المصدر نفسه يُزامَن حتى لا يُعاد الاسم بعد انقطاع عملية سُجّلت منتهية.
        fsyncDirectory(src.parent)
    }

    /**
     * حذف شجرة دون اتباع الروابط الرمزية (يُحذف الرابط نفسه لا هدفه). يتسامح مع غياب الجذر.
     * يُفحص الإلغاء عند كل مجلد وملف؛ ما حُذف قبل الإلغاء لا يعود، ويمكن إكمال
     * الحذف بأمان بإعادة العملية على الشجرة المتبقية.
     */
    fun deleteTree(root: Path, checkCancelled: () -> Unit = {}) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                checkCancelled()
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
