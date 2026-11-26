package org.openfilemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.openfilemanager.core.pdf.PdfFile
import org.openfilemanager.core.pdf.PdfFormatException
import org.openfilemanager.core.pdf.PdfStream

/**
 * اختبارات انحدار لإصلاحات المراجعة التقنية 2026-11-23 (F-04…F-11 في :core):
 * كل اختبار يثبت سلوك الإصلاح لا الحالة القديمة — انظر REVIEW.md لخريطة العيب→الاختبار.
 */
class TechnicalReviewFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    // ---- F-06: تعمية المسارات مع المسافات والعربية والرموز ----

    @Test fun redactHidesPathsWithSpacesAndArabicAndEmoji() {
        val cases = listOf(
            "فشل النسخ إلى /storage/emulated/0/My Photos/private file.pdf (Is a directory)" to listOf("My Photos", "private file"),
            "تعذر فتح C:\\Users\\Me\\My Docs\\ملف مهم.docx: رفض الوصول" to listOf("My Docs", "مهم"),
            "moved into /sdcard/التنزيلات/مجلد الفرز الجديد" to listOf("التنزيلات", "الفرز"),
            "emoji /storage/0/📁 صور/صورتي 🌟.png copied" to listOf("صورتي"),
            "src=/a/b c.tar.gz state=RUNNING" to listOf("b c.tar.gz")
        )
        for ((msg, secrets) in cases) {
            val redacted = PathRedactor.redact(msg)
            for (s in secrets) assertFalse("leaked \"$s\" in: $redacted", redacted.contains(s))
            assertTrue("detector found leftovers in: $redacted", PathRedactor.containsNoRawPath(redacted))
        }
    }

    @Test fun redactKeepsNonPathTextIntact() {
        assertEquals("no path here at all", PathRedactor.redact("no path here at all"))
        assertEquals("الوقت 12:30 والسبب مجهول", PathRedactor.redact("الوقت 12:30 والسبب مجهول"))
    }

    @Test fun containsNoRawPathDetectorIsIndependentAndBroader() {
        // كاشف مستقل عن خوارزمية التعمية: مكوّن واحد بلا مسافات يكفيه —
        // ما تنجح التعمية في إخفائه بالكاد، يكشف الكاشف ما فاتها (لا عمى مزدوج).
        assertFalse(PathRedactor.containsNoRawPath("/single"))
        assertFalse(PathRedactor.containsNoRawPath("leftover Docs\\ملف"))
        // الرموز المعمّاة نظيفة دائماً.
        val token = PathRedactor.token("/storage/emulated/0/x/y")
        assertTrue(PathRedactor.isToken(token))
        assertTrue(PathRedactor.containsNoRawPath("copied to $token ok"))
    }

    // ---- F-08: بروتوكول الاستبدال بلا نقل ذري ----

    /** محاكاة وسائط لا تدعم ATOMIC_MOVE (FAT/exFAT): نقل عادي بالعقد نفسه. */
    private val nonAtomicMove = { s: Path, t: Path, replace: Boolean ->
        if (replace) Files.move(s, t, StandardCopyOption.REPLACE_EXISTING)
        else {
            if (Files.exists(t, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(t.toString())
            Files.move(s, t)
        }
        Unit
    }

    @Test fun moveReplacingSucceedsWithoutAtomicMoveSupport() {
        val d = dir("mr")
        val dst = write(d.resolve("target.txt"), "old-content")
        val src = write(d.resolve("src.txt"), "new-content")
        val engine = FileOpEngine(MemoryOpJournal())
        engine.moveReplacing(src, dst, replaceExisting = true, move = nonAtomicMove)
        assertEquals("new-content", Files.readString(dst))
        assertFalse(Files.exists(src))
        val leftovers = Files.list(d).use { s -> s.anyMatch { it.fileName.toString().startsWith(".fm-replaced-") } }
        assertFalse("backup must be cleaned after success", leftovers)
    }

    @Test fun moveReplacingRollsBackWhenNewMoveFailsWithoutAtomicMoveSupport() {
        val d = dir("mr2")
        val dst = write(d.resolve("target.txt"), "old-content")
        val missing = d.resolve("missing.txt")
        val engine = FileOpEngine(MemoryOpJournal())
        try {
            engine.moveReplacing(missing, dst, replaceExisting = true, move = nonAtomicMove)
            fail()
        } catch (_: java.nio.file.NoSuchFileException) {
        }
        assertEquals("old content must survive a failed replace", "old-content", Files.readString(dst))
        val leftovers = Files.list(d).use { s -> s.anyMatch { it.fileName.toString().startsWith(".fm-replaced-") } }
        assertFalse("rollback must remove the backup", leftovers)
    }

    // ---- F-11: سقوف التعداد ----

    @Test fun enumerationCapRefusesHugeTreesWithClearFailure() {
        val src = dir("cap-src")
        repeat(50) { i -> write(src.resolve("f$i.txt"), "x") }
        val dst = dir("cap-dst")
        val engine = FileOpEngine(MemoryOpJournal(), maxEnumeratedItems = 10)
        try {
            runBlocking { engine.copy(listOf(src), dst, ConflictPolicy.SKIP) }
            fail()
        } catch (e: IOException) {
            assertTrue("clear message expected, got: ${e.message}", e.message!!.contains("enumeration exceeded"))
        }
        val published = Files.list(dst).use { it.count() }
        assertEquals("nothing must be published when enumeration is refused", 0, published)
    }

    // ---- F-05: تصادم ملف/مجلد ضمني بعد بناء المجلدات الضمنية ----

    private fun zip(path: Path, vararg entries: Pair<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
    }

    @Test fun implicitParentClashAfterMissingDirsIsRejected() {
        val archive = tmp.newFile("clash.zip").toPath()
        zip(archive, "a" to "file", "a/b.txt" to "nested")
        val load = ZipModel.load(archive)
        assertFalse("implicit file/folder clash must be refused", load.editable)
        assertEquals("file-folder-clash", load.reason)
    }

    @Test fun explicitFileAndFolderClashStillRejectedAndCleanLayoutStillLoads() {
        val explicitClash = tmp.newFile("explicit.zip").toPath()
        zip(explicitClash, "a" to "file", "a/" to "", "a/b.txt" to "nested")
        assertEquals("file-folder-clash", ZipModel.load(explicitClash).reason)

        val clean = tmp.newFile("clean.zip").toPath()
        zip(clean, "a/" to "", "a/b.txt" to "nested")
        val load = ZipModel.load(clean)
        assertTrue(load.editable)
        assertEquals(2, load.model!!.entries().size)
    }

    @Test fun planRefusesDuplicateEntryNamesFromDifferentSources() {
        val d1 = dir("p1"); write(d1.resolve("same.txt"), "1")
        val d2 = dir("p2"); write(d2.resolve("same.txt"), "2")
        try {
            ZipCreator().plan(listOf(d1.resolve("same.txt"), d2.resolve("same.txt")))
            fail()
        } catch (e: ZipCreateException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test fun planRefusesFileAndFolderRootWithTheSameName() {
        val pa = dir("pa"); Files.createDirectories(pa.resolve("item"))
        val pb = dir("pb"); write(pb.resolve("item"), "x")
        try {
            ZipCreator().plan(listOf(pa.resolve("item"), pb.resolve("item")))
            fail()
        } catch (e: ZipCreateException) {
            assertTrue(e.message!!.contains("clash"))
        }
    }

    @Test fun planAcceptsNormalDistinctSources() {
        val d = dir("ok"); write(d.resolve("one.txt"), "1"); write(d.resolve("two.txt"), "2")
        val entries = ZipCreator().plan(listOf(d.resolve("one.txt"), d.resolve("two.txt")))
        assertEquals(listOf("one.txt", "two.txt"), entries.map { it.entryName })
    }

    @Test fun planCapRefusesBeyondConfiguredLimit() {
        val d = dir("cap")
        repeat(6) { i -> write(d.resolve("f$i.txt"), "x") }
        try {
            ZipCreator(maxPlanEntries = 5).plan(d.toFile().listFiles()!!.map { it.toPath() })
            fail()
        } catch (e: ZipCreateException) {
            assertTrue(e.message!!.contains("entry limit"))
        }
    }

    // ---- F-04: حدود إخراج PDF على كل المرشحات والبيانات الخام ----

    /** يبني PDF صحيحاً بأجسام معطاة (بايتات خام لكل جسم) مع xref سليم. */
    private fun buildPdf(objects: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        val offsets = HashMap<Int, Int>()
        w("%PDF-1.4\n")
        for ((num, body) in objects) {
            offsets[num] = out.size()
            w("$num 0 obj\n")
            out.write(body)
            w("\nendobj\n")
        }
        val xrefStart = out.size()
        val maxNum = objects.maxOf { it.first }
        w("xref\n0 ${maxNum + 1}\n")
        w("0000000000 65535 f \n")
        for (n in 1..maxNum) w("%010d 00000 n \n".format(offsets.getValue(n)))
        w("trailer\n<< /Size ${maxNum + 1} /Root 2 0 R >>\n")
        w("startxref\n$xrefStart\n%%EOF\n")
        return out.toByteArray()
    }

    private fun streamObj(filter: String, data: ByteArray): ByteArray =
        // «/Filter /FlateDecode» — أسماء المرشحات أسماء PDF حقيقية (بالشرطة المائلة)؛
        // بدونه يقرأ المحلل قيمة عارية فتُتجاهل السلسلة ويُرجع الناتج الخام كما هو.
        "<< /Filter /$filter /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) +
            data + "\nendstream".toByteArray(Charsets.ISO_8859_1)

    private fun openPdf(vararg objects: Pair<Int, ByteArray>): PdfFile =
        PdfFile(ByteBuffer.wrap(buildPdf(objects.toList())))

    private fun contentStream(file: PdfFile, num: Int): PdfStream = file.obj(num) as PdfStream

    @Test fun flateDecodeEnforcesOutputLimit() {
        val big = ByteArray(2 * 1024 * 1024) // صفر متكرر: ضغط عالٍ وناتج منفوخ ضخم
        val deflated = ByteArrayOutputStream().let { bos ->
            val def = Deflater()
            def.setInput(big); def.finish()
            val buf = ByteArray(8192)
            while (!def.finished()) bos.write(buf, 0, def.deflate(buf))
            def.end(); bos.toByteArray()
        }
        val file = openPdf(1 to streamObj("FlateDecode", deflated), 2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1))
        try {
            file.decode(contentStream(file, 1), maxOutput = 1024 * 1024)
            fail()
        } catch (e: PdfFormatException) {
            assertEquals("stream too large", e.message)
        }
        // السقف الأوسع ينجح ويعيد الناتج كاملاً.
        assertEquals(big.size, file.decode(contentStream(file, 1), maxOutput = 4 * 1024 * 1024).size)
    }

    @Test fun asciiHexDecodeEnforcesOutputLimit() {
        val hex = "41".repeat(1500) // 3000 خانة سدس عشرية → 1500 بايت منفوخة
        val file = openPdf(1 to streamObj("ASCIIHexDecode", hex.toByteArray(Charsets.ISO_8859_1)), 2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1))
        try {
            file.decode(contentStream(file, 1), maxOutput = 100)
            fail()
        } catch (e: PdfFormatException) {
            assertEquals("stream too large", e.message)
        }
        assertEquals(1500, file.decode(contentStream(file, 1), maxOutput = 64 * 1024 * 1024).size)
    }

    @Test fun ascii85DecodeEnforcesOutputLimit() {
        // كل 'z' يكتب 4 أصفار: بيانات صغيرة بناتج منفوخ فوق الحد.
        val a85 = "<~" + "z".repeat(60) + "~>"
        val file = openPdf(1 to streamObj("ASCII85Decode", a85.toByteArray(Charsets.ISO_8859_1)), 2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1))
        try {
            file.decode(contentStream(file, 1), maxOutput = 16)
            fail()
        } catch (e: PdfFormatException) {
            assertEquals("stream too large", e.message)
        }
        assertEquals(240, file.decode(contentStream(file, 1), maxOutput = 64 * 1024 * 1024).size)
    }

    @Test fun filterChainLimitIsCumulativeNotPerStage() {
        val mid = ByteArray(2000) { 'A'.code.toByte() }
        val hex = mid.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.ISO_8859_1)
        val deflated = ByteArrayOutputStream().let { bos ->
            val def = Deflater()
            def.setInput(hex); def.finish()
            val buf = ByteArray(8192)
            while (!def.finished()) bos.write(buf, 0, def.deflate(buf))
            def.end(); bos.toByteArray()
        }
        val dict = "<< /Filter [/FlateDecode /ASCIIHexDecode] /Length ${deflated.size} >>\nstream\n"
        val body = dict.toByteArray(Charsets.ISO_8859_1) + deflated + "\nendstream".toByteArray(Charsets.ISO_8859_1)
        val file = openPdf(1 to body, 2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1))
        try {
            file.decode(contentStream(file, 1), maxOutput = 3000)
            fail()
        } catch (e: PdfFormatException) {
            assertEquals("stream too large", e.message)
        }
        assertEquals(2000, file.decode(contentStream(file, 1), maxOutput = 64 * 1024 * 1024).size)
    }

    @Test fun rawStreamLengthIsCappedAndBoundedByActualBytes() {
        val small = "hello".toByteArray(Charsets.ISO_8859_1)
        val buf = ByteBuffer.wrap(small)
        // طول معلن فوق السقف: رفض قبل أي تخصيص. (جدول xref المُنشئ يستدعي تعبئة
        // كل الأرقام 1..maxNum فلا يُبنى إلا بجسمي 1 و 2.)
        try {
            PdfFile(ByteBuffer.wrap(buildPdf(listOf(
                1 to "<< /Type /Dummy >>".toByteArray(Charsets.ISO_8859_1),
                2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1)
            ))))
                .rawBytes(PdfStream(emptyMap<String, Any?>(), 0, 200_000_000, buf))
            fail()
        } catch (e: PdfFormatException) {
            assertEquals("raw stream too large", e.message)
        }
        // طول معلن أكبر من الملف الفعلي: التخصيص مقيد بالمتوفر لا بالمعلن.
        val file = PdfFile(ByteBuffer.wrap(buildPdf(listOf(
            1 to "<< /Type /Dummy >>".toByteArray(Charsets.ISO_8859_1),
            2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1)
        ))))
        val raw = file.rawBytes(PdfStream(emptyMap<String, Any?>(), 0, 10_000_000, buf))
        assertEquals(5, raw.size)
        assertEquals("hello", String(raw, Charsets.ISO_8859_1))
    }
}
