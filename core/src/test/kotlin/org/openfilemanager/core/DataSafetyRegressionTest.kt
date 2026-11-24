package org.openfilemanager.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** اختبارات انحدار لعيوب P0 (F1–F4) من REVIEW.md. */
class DataSafetyRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun names(root: Path): Set<String> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.toList().toSet() }

    private fun zip(path: Path, vararg entries: Pair<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
    }

    // ---- T1 / F1 ----
    @Test fun keepBothOnNestedConflictPlacesFilesUnderAlternativeWithoutDuplicatedPrefix() {
        val src = dir("src")
        val dst = dir("dst")
        write(src.resolve("sub/inner/file.txt"), "new")
        write(dst.resolve("sub/inner"), "I am a file blocking the folder") // نفس مسار المجلد المتداخل لكنه ملف

        val engine = FileOpEngine(MemoryOpJournal())
        runBlocking { engine.copy(listOf(src.resolve("sub")), dst, ConflictPolicy.KEEP_BOTH) }

        assertEquals("I am a file blocking the folder", Files.readString(dst.resolve("sub/inner")))
        assertEquals("new", Files.readString(dst.resolve("sub/inner (1)/file.txt")))
        assertFalse("duplicated prefix", Files.exists(dst.resolve("sub/inner (1)/inner")))
    }

    // ---- T2 / F2 (السلوك العام؛ حالة FAT الفعلية تحتاج نظام ملفات غير حساس للحالة) ----
    @Test fun caseOnlyRenameKeepsSingleFile() {
        val d = dir("r")
        write(d.resolve("Photo.JPG"), "x")
        val out = SafeRename.rename(d.resolve("Photo.JPG"), "photo.jpg")
        assertEquals("photo.jpg", out.fileName.toString())
        assertEquals(setOf("photo.jpg"), names(d))
    }

    @Test fun renameOntoDifferentExistingFileStillRefused() {
        val d = dir("r2")
        write(d.resolve("a.txt"), "1"); write(d.resolve("A2.txt"), "2")
        try { SafeRename.rename(d.resolve("a.txt"), "A2.txt"); fail() } catch (_: java.nio.file.FileAlreadyExistsException) {}
        assertEquals(setOf("a.txt", "A2.txt"), names(d))
    }

    // ---- T3 / F3 ----
    @Test fun inPlaceFallbackRestoresOriginalWhenNewContentIsCorrupt() {
        val d = dir("z")
        val archive = d.resolve("a.zip")
        zip(archive, "one.txt" to "1")
        val before = Files.readAllBytes(archive)
        val corrupt = d.resolve("corrupt.tmp")
        Files.write(corrupt, "not a zip".toByteArray())
        val expected = listOf(ZipEntryInfo("one.txt", 1, 0, ZipSource.Original("one.txt")))

        try { ZipRewriter.overwriteWithBackup(corrupt, archive, expected); fail("must fail verification") } catch (_: IOException) {}

        assertArrayEquals(before, Files.readAllBytes(archive))
        assertEquals("no backup left behind", setOf("a.zip", "corrupt.tmp"), names(d))
    }

    @Test fun inPlaceFallbackSucceedsAndRemovesBackup() {
        val d = dir("z2")
        val archive = d.resolve("a.zip")
        zip(archive, "old.txt" to "1")
        val fresh = d.resolve("fresh.tmp")
        zip(fresh, "new.txt" to "2")
        ZipRewriter.overwriteWithBackup(fresh, archive, listOf(ZipEntryInfo("new.txt", 1, 0, ZipSource.Original("new.txt"))))
        java.util.zip.ZipFile(archive.toFile()).use { assertTrue(it.getEntry("new.txt") != null && it.getEntry("old.txt") == null) }
        assertEquals(setOf("a.zip", "fresh.tmp"), names(d))
    }

    // ---- T4 / F4 (الوظيفة والنظافة؛ ترتيب fsync لا يُرصد على نظام ملفات حقيقي) ----
    @Test fun zipCreateThenExtractRoundTripLeavesNoTemps() {
        val src = dir("s"); write(src.resolve("f/a.txt"), "hello")
        val out = dir("o").resolve("x.zip")
        ZipCreator().create(listOf(src.resolve("f")), out)
        val dest = dir("e")
        ArchiveExtractor().extract(out, dest)
        assertEquals("hello", Files.readString(dest.resolve("x/f/a.txt").takeIf { Files.exists(it) } ?: dest.resolve("x/a.txt")))
        assertTrue(Files.list(dest).use { s -> s.noneMatch { it.fileName.toString().startsWith(".fm-") } })
    }
}
