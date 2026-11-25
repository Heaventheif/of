package org.openfilemanager.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** Regression tests for the minSdk-30 API fixes and the copy/move data-safety fixes. */
class MinSdkAndTreeFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Returns one byte per call, like a slow stream: breaks code that assumes full reads. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val v = read(); if (v < 0) return -1
            b[off] = v.toByte(); return 1
        }
        override fun skip(n: Long): Long = 0 // forces skipFully to fall back to read()
    }

    @Test fun readUpTo_fillsBufferAcrossShortReads() {
        val buf = ByteArray(5)
        assertEquals(5, Trickle(byteArrayOf(1, 2, 3, 4, 5, 6)).readUpTo(buf, 0, 5))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), buf.toList())
        assertEquals(3, Trickle(byteArrayOf(1, 2, 3)).readUpTo(buf, 0, 5))
    }

    @Test fun skipFully_skipsExactlyAndThrowsOnTruncation() {
        val input = Trickle(byteArrayOf(1, 2, 3, 4))
        input.skipFully(3)
        assertEquals(4, input.read())
        var thrown = false
        try { Trickle(byteArrayOf(1)).skipFully(5) } catch (e: EOFException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun archiveFormatDetect_zipMagic() {
        val f = tmp.newFile("a.zip").toPath()
        java.util.zip.ZipOutputStream(Files.newOutputStream(f)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("x.txt")); z.write(1); z.closeEntry()
        }
        assertEquals(ArchiveFormat.Kind.ZIP, ArchiveFormat.detect(f))
    }

    @Test fun move_replace_neverDeletesADirectoryTree() = runBlocking {
        val root = tmp.newFolder("m").toPath()
        val src = root.resolve("report.pdf"); Files.write(src, byteArrayOf(1))
        val out = Files.createDirectory(root.resolve("out"))
        val dir = Files.createDirectory(out.resolve("report.pdf"))
        val keep = dir.resolve("notes.txt"); Files.write(keep, "keep".toByteArray())
        FileOpEngine(MemoryOpJournal()).move(listOf(src), out, ConflictPolicy.REPLACE)
        assertTrue("directory content must survive", Files.exists(keep))
        assertTrue("source must be untouched", Files.exists(src))
    }

    @Test fun copy_preservesModifiedTime() = runBlocking {
        val root = tmp.newFolder("c").toPath()
        val src = root.resolve("a.txt"); Files.write(src, "abc".toByteArray())
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_600_000_000_000L))
        val out = Files.createDirectory(root.resolve("out"))
        FileOpEngine(MemoryOpJournal()).copy(listOf(src), out, ConflictPolicy.REPLACE)
        assertEquals(Files.getLastModifiedTime(src), Files.getLastModifiedTime(out.resolve("a.txt")))
    }

    @Test fun copy_keepsGoingPastAnUnreadableDirectory() = runBlocking {
        val root = tmp.newFolder("u").toPath()
        val src = Files.createDirectory(root.resolve("src"))
        Files.write(src.resolve("ok.txt"), "ok".toByteArray())
        val locked = Files.createDirectory(src.resolve("locked"))
        locked.toFile().setReadable(false, false); locked.toFile().setExecutable(false, false)
        try {
            assumeTrue("cannot drop read permission here", !Files.isReadable(locked))
            val out = Files.createDirectory(root.resolve("out"))
            FileOpEngine(MemoryOpJournal()).copy(listOf(src), out, ConflictPolicy.REPLACE)
            assertTrue(Files.exists(out.resolve("src/ok.txt")))
        } finally {
            locked.toFile().setReadable(true, false); locked.toFile().setExecutable(true, false)
        }
    }
}
