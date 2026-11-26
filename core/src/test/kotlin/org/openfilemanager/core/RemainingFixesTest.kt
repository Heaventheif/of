package org.openfilemanager.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class RemainingFixesTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun dir(n: String): Path = tmp.newFolder(n).toPath()

    @Test fun hiddenFolderIsNotSearchedWhenHiddenExcluded() {
        val root = dir("s")
        Files.createDirectories(root.resolve(".git"))
        Files.writeString(root.resolve(".git/config.txt"), "x")
        Files.writeString(root.resolve("config.txt"), "y")
        val engine = SearchEngine()
        val off = engine.search(root, SearchEngine.namePredicate("config", false), includeHidden = false)
        assertEquals(listOf("config.txt"), off.hits.map { it.name })
        val on = engine.search(root, SearchEngine.namePredicate("config", true), includeHidden = true)
        assertEquals(2, on.hits.size)
    }

    @Test fun windows1256FileKeepsItsEncodingOnRoundTrip() {
        val cs = java.nio.charset.Charset.forName("windows-1256")
        val original = "مرحبا بالعالم".toByteArray(cs)
        val d = TextFile.decode(original)
        assertEquals("مرحبا بالعالم", d.text)
        assertEquals(cs, d.charset)
        assertTrue(d.guessed)
        assertArrayEquals(original, TextFile.encode(d.text, d.charset, d.bom))
    }

    @Test fun utf8WithBomKeepsBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "مرحبا".toByteArray()
        val d = TextFile.decode(bytes)
        assertEquals("مرحبا", d.text)
        assertFalse(d.guessed)
        assertArrayEquals(bytes, TextFile.encode(d.text, d.charset, d.bom))
    }

    @Test fun utf16WithBomRoundTrips() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "ab".toByteArray(Charsets.UTF_16LE)
        val d = TextFile.decode(bytes)
        assertEquals("ab", d.text)
        assertArrayEquals(bytes, TextFile.encode(d.text, d.charset, d.bom))
    }

    @Test fun atomicWriteReplacesAndLeavesNoTemp() {
        val d = dir("w"); val f = d.resolve("a.txt")
        Files.writeString(f, "old")
        AtomicIO.atomicWrite(f) { it.write("new".toByteArray()) }
        assertEquals("new", Files.readString(f))
        assertEquals(listOf("a.txt"), Files.list(d).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test fun journalIgnoresTruncatedLastLineAndDurableAppendsReadBack() {
        val jd = dir("j")
        val j = FileOpJournal(jd)
        val rec = OpRecord("op1", OpKind.COPY, OpState.RUNNING, listOf("/a"), "/b", 1L)
        val item = OpItemRecord("op1", 0, OpItemKind.FILE, ItemState.PENDING, "/a", "/b/a", size = 1L)
        j.startOp(rec, listOf(item))
        j.updateItem("op1", 0, ItemState.DONE)
        assertEquals(ItemState.DONE, j.items("op1").single().state)
        // سطر أخير مبتور (انقطاع في منتصف الكتابة) يُتجاهل ولا يغيّر الحالة السابقة.
        val log = Files.list(jd).use { st -> st.filter { it.fileName.toString().startsWith("op-") }.toList().single() }
        Files.write(log, "I\t0\t0\tFILE\tPEND".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(ItemState.DONE, FileOpJournal(jd).items("op1").single().state)
        j.finishOp("op1", OpState.FINISHED, 1, 0, 0, "")
        assertEquals(OpState.FINISHED, j.op("op1")?.state)
    }
}
