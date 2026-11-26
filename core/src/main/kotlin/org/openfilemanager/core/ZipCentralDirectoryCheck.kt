package org.openfilemanager.core

import java.io.RandomAccessFile
import java.nio.file.Path

object ZipCentralDirectoryCheck {

    /**
     * سقف بايتات الدليل المركزي نفسه (64 MiB — من variant B): عدد العناصر
     * المعلن وحده لا يكفي؛ أرشيف بعدد تحت السقف وبأسماء عملاقة قد يكون
     * فهرسته المكاملة أكبر من الذاكرة المقبولة. يتجاوزه يُرفض قبل الفتح.
     */
    const val MAX_DIRECTORY_BYTES: Long = 64L * 1024 * 1024

    /** حقول EOCD كما قُرأت. */
    data class Info(
        val declaredEntries: Long,
        val centralDirectorySize: Long,
        val centralDirectoryOffset: Long,
        val zip64: Boolean
    )

    sealed interface Verdict {
        data class Pass(val info: Info) : Verdict
        data class Unreadable(val reason: String) : Verdict
        data class OverLimit(val declaredEntries: Long) : Verdict
        data class CentralDirectoryTooLarge(val size: Long, val cap: Long) : Verdict
        data class Inconsistent(val problem: String) : Verdict
    }

    private const val EOCD_SIG = 0x06054b50L
    private const val ZIP64_EOCD_SIG = 0x06064b50L
    private const val ZIP64_LOCATOR_SIG = 0x07064b50L
    private const val CEN_SIG = 0x02014b50L
    private const val CEN_HEADER_MIN = 46
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_MIN_SIZE = 56

    /**
     * يفحص ذيل [file] ويحكم قبل أي فتح. [maxEntries] سقف العناصر نفسه لدى
     * المتصل، و[maxDirectoryBytes] سقف بايتات الدليل المركزي (اختبارية
     * افتراضياً على [MAX_DIRECTORY_BYTES]).
     */
    fun check(file: Path, maxEntries: Int, maxDirectoryBytes: Long = MAX_DIRECTORY_BYTES): Verdict {
        return try {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val length = raf.length()
                if (length < EOCD_MIN) return Verdict.Unreadable("file shorter than EOCD")
                val windowSize = (EOCD_MIN + MAX_COMMENT + ZIP64_LOCATOR_SIZE).toLong()
                val windowStart = maxOf(0L, length - windowSize)
                val window = ByteArray((length - windowStart).toInt())
                raf.seek(windowStart)
                raf.readFully(window)

                // آخر EOCD توقيعه صحيح وطول تعليقه يصل نهاية الملف بالضبط
                // (تعليق قد يحمل توقيع EOCD زوراً — المطابقة مع النهاية تحسمه).
                var eocdRel = -1
                for (i in window.size - EOCD_MIN downTo 0) {
                    if (le32(window, i) != EOCD_SIG) continue
                    val commentLen = le16(window, i + EOCD_MIN - 2)
                    if (windowStart + i + EOCD_MIN + commentLen == length) { eocdRel = i; break }
                }
                if (eocdRel < 0) return Verdict.Unreadable("no EOCD with matching comment length")
                val eocdAbs = windowStart + eocdRel

                var declared = le16(window, eocdRel + 10).toLong()
                var cdSize = le32(window, eocdRel + 12)
                var cdOffset = le32(window, eocdRel + 16)
                var zip64 = false

                // محدد ZIP64: 20 بايت قبالة EOCD مباشرة.
                if (eocdAbs >= ZIP64_LOCATOR_SIZE.toLong()) {
                    val loc = ByteArray(ZIP64_LOCATOR_SIZE)
                    raf.seek(eocdAbs - ZIP64_LOCATOR_SIZE.toLong())
                    raf.readFully(loc)
                    if (le32(loc, 0) == ZIP64_LOCATOR_SIG) {
                        val recordOffset = le64(loc, 8)
                        if (recordOffset in 0 until length &&
                            recordOffset + ZIP64_EOCD_MIN_SIZE <= length
                        ) {
                            val rec = ByteArray(ZIP64_EOCD_MIN_SIZE)
                            raf.seek(recordOffset)
                            raf.readFully(rec)
                            if (le32(rec, 0) == ZIP64_EOCD_SIG) {
                                declared = le64(rec, 32)
                                cdSize = le64(rec, 40)
                                cdOffset = le64(rec, 48)
                                zip64 = true
                            }
                        }
                        if (!zip64 &&
                            (declared == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL)
                        ) {
                            // محدد ZIP64 موجود لكن سجله غير مقروء، وحقول EOCD
                            // القديمة قيم «انظر ZIP64» — لا حسم هنا؛ المسار
                            // القديم يرمي خطأه كما اليوم.
                            return Verdict.Unreadable("zip64 locator present but record unreadable")
                        }
                    }
                }

                if (declared > maxEntries.toLong()) return Verdict.OverLimit(declared)
                // الاتساق أولاً: حقول لا تتصف مع طول الملف تلفٌ لا حدُّ مورد،
                // فتصنيفها Inconsistent يسبق السقف البايتي (حجمٌ فوق السقف
                // وفوق طول الملف معاً = تلف).
                if (declared < 0 || cdSize < 0 || cdOffset < 0 ||
                    cdSize > length || cdOffset > length
                ) {
                    return Verdict.Inconsistent(
                        "central directory size/offset (${cdSize}/${cdOffset}) exceed file length $length"
                    )
                }
                // ثم السقف البايتي: EOCD متسق لكن فهرسته المكاملة أكبر من
                // المقبول — قنبلة المورد التي يستهدفها الفحص أصلاً.
                if (cdSize > maxDirectoryBytes) {
                    return Verdict.CentralDirectoryTooLarge(cdSize, maxDirectoryBytes)
                }
                // (E4) العدّ الفعلي لرؤوس الدليل المركزي: العدد المعلن وحده كان يُقيّم، فتعريفٌ أقل من الحقيقة تجاوز سقف العناصر بلا ملاحظة (ZipFile يعدّ الفعلي عند…
                scanCentralHeaders(raf, cdOffset, cdSize, maxEntries)?.let { return it }
                Verdict.Pass(Info(declared, cdSize, cdOffset, zip64))
            }
        } catch (e: Exception) {
            Verdict.Unreadable("tail read failed: ${e.message}")
        }
    }

    /** (E4) يمشي الدليل المركزي رأساً رأساً (توقيع 0x02014b50 ثم أطوال الاسم/الإضافات/التعليق) ويعدّ الرؤوس الفعلية مع توقف مبكر عند [maxEntries] + 1. */
    private fun scanCentralHeaders(
        raf: RandomAccessFile,
        cdOffset: Long,
        cdSize: Long,
        maxEntries: Int
    ): Verdict? {
        var pos = cdOffset
        var remaining = cdSize
        var actual = 0L
        while (remaining >= CEN_HEADER_MIN) {
            val header = ByteArray(CEN_HEADER_MIN)
            raf.seek(pos)
            raf.readFully(header)
            if (le32(header, 0) != CEN_SIG) return null
            val nameLen = le16(header, 28)
            val extraLen = le16(header, 30)
            val commentLen = le16(header, 32)
            val entrySize = CEN_HEADER_MIN + nameLen + extraLen + commentLen
            if (entrySize > remaining) return null
            actual++
            if (actual > maxEntries) return Verdict.OverLimit(actual)
            pos += entrySize
            remaining -= entrySize
        }
        return null
    }

    private fun le16(b: ByteArray, pos: Int): Long =
        (b[pos].toLong() and 0xFF) or ((b[pos + 1].toLong() and 0xFF) shl 8)

    private fun le32(b: ByteArray, pos: Int): Long =
        le16(b, pos) or (le16(b, pos + 2) shl 16)

    private fun le64(b: ByteArray, pos: Int): Long =
        le32(b, pos) or (le32(b, pos + 4) shl 32)
}
