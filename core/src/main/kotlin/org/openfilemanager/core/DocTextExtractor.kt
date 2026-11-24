package org.openfilemanager.core

import java.io.File

/** Why a legacy .doc could not be read. */
class DocFormatException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_OLE, ENCRYPTED, UNSUPPORTED, TOO_LARGE, CORRUPT }
}

/**
 * Minimal text-only reader for legacy Word 97-2003 (.doc) files.
 * Parses the OLE2 container, the FIB and the piece table; no formatting, images or tables.
 */
object DocTextExtractor {
    private const val MAX_FILE = 50L * 1024 * 1024
    private const val MAX_CHARS = 5_000_000
    private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    fun extract(file: File): String {
        if (file.length() > MAX_FILE) throw DocFormatException(DocFormatException.Reason.TOO_LARGE)
        return extract(file.readBytes())
    }

    fun extract(data: ByteArray): String {
        if (data.size < 512 || !SIGNATURE.indices.all { data[it] == SIGNATURE[it] }) {
            throw DocFormatException(DocFormatException.Reason.NOT_OLE)
        }
        try {
            val cfb = Cfb(data)
            val word = cfb.stream("WordDocument") ?: throw DocFormatException(DocFormatException.Reason.UNSUPPORTED)
            return readText(cfb, word)
        } catch (e: DocFormatException) {
            throw e
        } catch (e: Exception) { // out-of-range reads on damaged files
            throw DocFormatException(DocFormatException.Reason.CORRUPT)
        }
    }

    // ---- Word binary format ----

    private fun readText(cfb: Cfb, w: ByteArray): String {
        if (u16(w, 0) != 0xA5EC) throw DocFormatException(DocFormatException.Reason.UNSUPPORTED) // Word 95 and older
        val flags = u16(w, 0x0A)
        if (flags and 0x0100 != 0) throw DocFormatException(DocFormatException.Reason.ENCRYPTED)
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table")
            ?: throw DocFormatException(DocFormatException.Reason.CORRUPT)

        var pos = 0x20
        pos += 2 + u16(w, pos) * 2                      // csw + fibRgW
        val cslw = u16(w, pos)
        val lwStart = pos + 2
        val ccpText = u32(w, lwStart + 3 * 4)           // main document character count
        pos = lwStart + cslw * 4
        val cbRgFcLcb = u16(w, pos)
        if (cbRgFcLcb <= 33) throw DocFormatException(DocFormatException.Reason.UNSUPPORTED)
        val fcClx = u32(w, pos + 2 + 33 * 8)
        val lcbClx = u32(w, pos + 2 + 33 * 8 + 4)
        if (lcbClx <= 0) throw DocFormatException(DocFormatException.Reason.CORRUPT)

        // Clx: skip Prc entries (0x01), then the Pcdt (0x02) holding the piece table.
        var c = fcClx
        while (table[c].toInt() == 0x01) c += 3 + u16(table, c + 1)
        if (table[c].toInt() != 0x02) throw DocFormatException(DocFormatException.Reason.CORRUPT)
        val lcb = u32(table, c + 1)
        val plc = c + 5
        val n = (lcb - 4) / 12
        if (n <= 0) throw DocFormatException(DocFormatException.Reason.CORRUPT)

        val out = StringBuilder()
        val fields = ArrayList<Boolean>() // true while inside a field's code part
        for (i in 0 until n) {
            val cpStart = u32(table, plc + i * 4)
            val cpEnd = minOf(u32(table, plc + (i + 1) * 4), ccpText)
            if (cpStart >= ccpText || cpEnd <= cpStart) continue
            val fcRaw = u32(table, plc + (n + 1) * 4 + i * 8 + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = fcRaw and 0x3FFFFFFF
            val count = cpEnd - cpStart
            if (compressed) {
                val start = fc / 2
                val text = String(w, start, count, charset("windows-1252"))
                for (ch in text) append(out, fields, ch)
            } else {
                for (k in 0 until count) append(out, fields, u16(w, fc + k * 2).toChar())
            }
            if (out.length > MAX_CHARS) break
        }
        return out.toString().trim()
    }

    private fun append(out: StringBuilder, fields: ArrayList<Boolean>, ch: Char) {
        when (ch.code) {
            0x13 -> { fields.add(true); return }
            0x14 -> { if (fields.isNotEmpty()) fields[fields.size - 1] = false; return }
            0x15 -> { if (fields.isNotEmpty()) fields.removeAt(fields.size - 1); return }
        }
        if (fields.any { it }) return // field instruction text (e.g. HYPERLINK ...)
        when (ch.code) {
            0x0D, 0x0B, 0x0C -> out.append('\n')
            0x07 -> if (out.isNotEmpty() && out.last() == '\t') out[out.length - 1] = '\n' else out.append('\t') // cell end / row end
            0x09, 0x0A -> out.append(ch)
            0x1E -> out.append('-')
            in 0x00..0x1F -> Unit
            else -> out.append(ch)
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)

    // ---- OLE2 / CFB container ----

    private class Cfb(private val d: ByteArray) {
        private val sectorSize = 1 shl u16(d, 0x1E)
        private val miniSectorSize = 1 shl u16(d, 0x20)
        private val miniCutoff = u32(d, 0x38)
        private val fat: IntArray
        private val miniFat: IntArray
        private val dir: List<Entry>
        private var miniStream: ByteArray = ByteArray(0)

        class Entry(val name: String, val type: Int, val start: Int, val size: Int)

        init {
            if (sectorSize != 512 && sectorSize != 4096) throw DocFormatException(DocFormatException.Reason.CORRUPT)
            // FAT sector list: 109 header entries, then the DIFAT chain
            val fatSectors = ArrayList<Int>()
            val numFat = u32(d, 0x2C)
            for (i in 0 until 109) {
                val s = u32(d, 0x4C + i * 4)
                if (s >= 0 && fatSectors.size < numFat) fatSectors.add(s)
            }
            var difat = u32(d, 0x44)
            var guard = 0
            while (difat >= 0 && fatSectors.size < numFat && guard++ < 100_000) {
                val off = sectorOffset(difat)
                for (i in 0 until sectorSize / 4 - 1) {
                    val s = u32(d, off + i * 4)
                    if (s >= 0 && fatSectors.size < numFat) fatSectors.add(s)
                }
                difat = u32(d, off + sectorSize - 4)
            }
            val per = sectorSize / 4
            fat = IntArray(fatSectors.size * per)
            for ((idx, s) in fatSectors.withIndex()) {
                val off = sectorOffset(s)
                for (i in 0 until per) fat[idx * per + i] = u32(d, off + i * 4)
            }
            // directory
            val dirBytes = readChain(u32(d, 0x30))
            val entries = ArrayList<Entry>()
            var p = 0
            while (p + 128 <= dirBytes.size) {
                val nameLen = u16(dirBytes, p + 0x40)
                val name = if (nameLen in 2..64) String(dirBytes, p, nameLen - 2, Charsets.UTF_16LE) else ""
                entries.add(Entry(name, dirBytes[p + 0x42].toInt(), u32(dirBytes, p + 0x74), u32(dirBytes, p + 0x78)))
                p += 128
            }
            dir = entries
            val root = entries.firstOrNull { it.type == 5 } ?: throw DocFormatException(DocFormatException.Reason.CORRUPT)
            miniStream = readChain(root.start).let { if (root.size in 0..it.size) it.copyOf(root.size) else it }
            val mf = readChain(u32(d, 0x3C))
            miniFat = IntArray(mf.size / 4) { u32(mf, it * 4) }
        }

        fun stream(name: String): ByteArray? {
            val e = dir.firstOrNull { it.type == 2 && it.name == name } ?: return null
            if (e.size < 0) return null
            return if (e.size < miniCutoff) readMini(e.start, e.size)
            else readChain(e.start).let { if (it.size > e.size) it.copyOf(e.size) else it }
        }

        private fun sectorOffset(id: Int) = (id + 1) * sectorSize

        private fun readChain(start: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < fat.size && guard++ <= fat.size) {
                val off = sectorOffset(s)
                if (off + sectorSize > d.size) { out.write(d, off, (d.size - off).coerceAtLeast(0)); break }
                out.write(d, off, sectorSize)
                s = fat[s]
            }
            return out.toByteArray()
        }

        private fun readMini(start: Int, size: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < miniFat.size && guard++ <= miniFat.size) {
                val off = s * miniSectorSize
                if (off + miniSectorSize > miniStream.size) break
                out.write(miniStream, off, miniSectorSize)
                s = miniFat[s]
            }
            return out.toByteArray().let { if (it.size > size) it.copyOf(size) else it }
        }

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }

}
