package org.openfilemanager.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * قراءة اتجاه EXIF من صور JPEG. التدوير حالة عرض فقط ولا يُعدَّل الملف.
 * نقرأ APP1/Exif يدوياً (بلا مكتبة) لأن هذا كله ما نحتاجه، ويعمل على JVM في الاختبارات.
 */
object ExifOrientation {

    /** 1..8 كما في معيار EXIF؛ 1 = طبيعي. */
    fun read(file: Path): Int {
        Files.newInputStream(file).use { input ->
            val head = ByteArray(2)
            if (input.readNBytes(head, 0, 2) < 2 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return 1
            while (true) {
                val m0 = input.read()
                if (m0 != 0xFF) return 1
                var marker = input.read()
                if (marker == -1) return 1
                while (marker == 0xFF) marker = input.read()
                if (marker == -1) return 1 // ملف مقطوع بعد 0xFF
                when {
                    marker == 0xD8 || (marker in 0xD0..0xD7) || marker == 0x01 -> continue
                    marker == 0xDA -> return 1
                }
                // (تصحيح) ملف مقطوع: قراءة -1 عند النهاية تُفسَّر 0xFF وتنتج طولاً
                // خاطئاً 65535 ثم EOFException من skipNBytes — نعيد الاتجاه الافتراضي.
                val lenHi = input.read()
                val lenLo = input.read()
                if (lenHi == -1 || lenLo == -1) return 1
                val len = ((lenHi and 0xFF) shl 8) or (lenLo and 0xFF)
                if (len < 2) return 1
                if (marker != 0xE1) {
                    runCatching { input.skipNBytes((len - 2).toLong()) }
                        .onFailure { return 1 } // مقطوع داخل المقطع: لا اتجاه معلوماً
                    continue
                }
                val payload = input.readNBytes(len - 2)
                return orientationFromExif(payload)
            }
        }
    }

    /** يحلل حمولة APP1 ويستخرج قيمة Orientation. */
    internal fun orientationFromExif(payload: ByteArray): Int {
        val exifHeader = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
        if (payload.size < exifHeader.size + 8) return 1
        for (i in exifHeader.indices) if (payload[i] != exifHeader[i]) return 1
        val tiff = payload.size - exifHeader.size
        val bigEndian = payload[exifHeader.size] == 0x4D.toByte() && payload[exifHeader.size + 1] == 0x4D.toByte()
        fun u16(off: Int): Int {
            val p = exifHeader.size + off
            if (p + 1 >= payload.size) return 0
            return if (bigEndian) ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
            else ((payload[p + 1].toInt() and 0xFF) shl 8) or (payload[p].toInt() and 0xFF)
        }

        fun u32(off: Int): Int {
            val p = exifHeader.size + off
            if (p + 3 >= payload.size) return 0
            return if (bigEndian)
                ((payload[p].toInt() and 0xFF) shl 24) or ((payload[p + 1].toInt() and 0xFF) shl 16) or
                    ((payload[p + 2].toInt() and 0xFF) shl 8) or (payload[p + 3].toInt() and 0xFF)
            else
                ((payload[p + 3].toInt() and 0xFF) shl 24) or ((payload[p + 2].toInt() and 0xFF) shl 16) or
                    ((payload[p + 1].toInt() and 0xFF) shl 8) or (payload[p].toInt() and 0xFF)
        }

        val ifdOffset = u32(4)
        val count = u16(ifdOffset)
        for (i in 0 until count) {
            val entry = ifdOffset + 2 + i * 12
            if (entry + 12 > tiff) break
            if (u16(entry) == 0x0112) {
                val value = u16(entry + 8)
                return if (value in 1..8) value else 1
            }
        }
        return 1
    }

    /** درجات التدوير اللازمة لعرض الصورة معتدلة (القيم الفردية 90 والزوجية 180... حسب المعيار). */
    fun rotationDegrees(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }
}

/** حجم بالبايت إلى بادئة ثنائية مقربة (واجهة المكتبة والخصائص). */
fun Long.toReadableSize(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(java.util.Locale.ROOT, "%.2f %s", value, units[index])
}
