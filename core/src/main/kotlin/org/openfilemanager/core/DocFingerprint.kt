package org.openfilemanager.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * هوية مستند مستقرة للقارئ: بصمة المحتوى (الحجم + أول 64KB + آخر 64KB)، لا الاسم ولا المسار.
 * فإعادة تسمية الملف أو نقله تُبقي العلامات وموضع القراءة، وملفان مختلفان بالاسم نفسه لا يلتبسان.
 * (نسختان مطابقتان بايتاً ببايت تتشاركان العلامات عمداً.)
 */
object DocFingerprint {
    private const val CHUNK = 64 * 1024

    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            md.update(size.toString().toByteArray())
            val buf = ByteArray(CHUNK)
            fun feed(offset: Long, len: Int) {
                raf.seek(offset)
                var read = 0
                while (read < len) {
                    val n = raf.read(buf, 0, len - read)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    read += n
                }
            }
            if (size <= 2L * CHUNK) {
                feed(0, size.toInt())
            } else {
                feed(0, CHUNK)
                feed(size - CHUNK, CHUNK)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return hex.substring(0, 32)
    }
}
