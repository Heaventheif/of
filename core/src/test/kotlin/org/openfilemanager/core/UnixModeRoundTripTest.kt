package org.openfilemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

class UnixModeRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun dir(n: String): Path = tmp.newFolder(n).toPath()

    /** ينشئ أرشيفًا يحمل وضع 0755 لملف و0700 لمجلد بتنسيق info-zip الخارجي. */
    private fun zipWithModes(archive: Path, fileMode: Int, dirMode: Int) {
        ZipArchiveOutputStream(Files.newOutputStream(archive)).use { out ->
            out.setEncoding(Charsets.UTF_8.name())
            // مجلد بوضع 0700
            val d = ZipArchiveEntry("dir/")
            d.setTime(0)
            d.externalAttributes = (((0x4000 or dirMode) shl 16) or (3 shl 0)).toLong()
            out.putArchiveEntry(d); out.closeArchiveEntry()
            // ملف بوضع 0755
            val f = ZipArchiveEntry("dir/script.sh")
            f.setTime(0)
            f.externalAttributes = (((0x8000 or fileMode) shl 16) or (3 shl 0)).toLong()
            out.putArchiveEntry(f)
            out.write("#!/bin/sh\n".toByteArray())
            out.closeArchiveEntry()
        }
    }

    private fun modeOf(archive: Path, name: String): Int {
        org.apache.commons.compress.archivers.zip.ZipFile(archive.toFile()).use { zf ->
            val e = zf.getEntry(name) ?: throw AssertionError("entry missing: $name")
            return ((e.externalAttributes ushr 16).toInt() and 0xFFF)
        }
    }

    @Test fun rewritingKeepsUnixModesOfExistingEntries() {
        val d = dir("m")
        val archive = d.resolve("a.zip")
        zipWithModes(archive, fileMode = 0x1ED /*0755*/, dirMode = 0x1C0 /*0700*/)
        assertEquals(0x1ED, modeOf(archive, "dir/script.sh"))
        assertEquals(0x1C0, modeOf(archive, "dir/"))

        // تحميل النموذج، تعديل اسم (يمر عبر مسار إعادة الكتابة كاملاً)، حفظ.
        val load = ZipModel.load(archive)
        assertTrue("editable", load.editable)
        val model = load.model ?: throw AssertionError("model null: ${load.reason}")
        model.rename("dir/script.sh", "run.sh")
        ZipRewriter.write(model)

        // الوضعان بقيا كما هما بعد إعادة الكتابة.
        assertEquals(0x1ED, modeOf(archive, "dir/run.sh"))
        assertEquals(0x1C0, modeOf(archive, "dir/"))
        // الملف القديم زال فعلاً.
        assertEquals(-1, org.apache.commons.compress.archivers.zip.ZipFile(archive.toFile()).use { zf ->
            if (zf.getEntry("dir/script.sh") == null) -1 else 0
        })
    }

    @Test fun addedFileCarriesItsPosixPermissions() {
        val d = dir("a")
        val archive = d.resolve("b.zip")
        zipWithModes(archive, fileMode = 0x1ED, dirMode = 0x1C0)

        // ملف جديد بصلاحيات rw------- (0600) يُضاف إلى الأرشيف.
        val src = d.resolve("secret.txt")
        Files.writeString(src, "x")
        Files.setPosixFilePermissions(src, PosixFilePermissions.fromString("rw-------"))

        val model = ZipModel.load(archive).model ?: throw AssertionError()
        model.add("", src)
        ZipRewriter.write(model)

        val mode = modeOf(archive, "secret.txt")
        assertEquals(0x180 /*0600*/, mode)
    }
}
