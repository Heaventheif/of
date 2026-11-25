package org.openfilemanager.core

import java.io.EOFException
import java.io.InputStream

/*
 * minSdk 30 compatibility: InputStream.readNBytes (API 33) and skipNBytes (API 34)
 * do not exist on older Android runtimes, and :core is a pure-JVM module so Lint
 * cannot flag them. Use these helpers instead.
 */

/** Reads up to [len] bytes into [buf]; returns the count read (< len only at EOF). */
internal fun InputStream.readUpTo(buf: ByteArray, off: Int, len: Int): Int {
    var total = 0
    while (total < len) {
        val n = read(buf, off + total, len - total)
        if (n < 0) break
        total += n
    }
    return total
}

/** Reads up to [len] bytes (bounded allocation); returns fewer only at EOF. */
internal fun InputStream.readUpTo(len: Int): ByteArray {
    val buf = ByteArray(len)
    val n = readUpTo(buf, 0, len)
    return if (n == len) buf else buf.copyOf(n)
}

/** Skips exactly [n] bytes or throws [EOFException]. */
internal fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) { left -= skipped; continue }
        if (read() < 0) throw EOFException("truncated")
        left--
    }
}
