package org.openfilemanager.core
import org.openfilemanager.core.CoreOpCode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.openfilemanager.core.pdf.PdfBudgetException
import org.openfilemanager.core.pdf.PdfFile
import org.openfilemanager.core.pdf.PdfFormatException
import org.openfilemanager.core.pdf.PdfResourceBudget
import org.openfilemanager.core.pdf.PdfStream
import org.openfilemanager.core.pdf.PdfTextDocument

/**
 * اختبارات انحدار لتحصين 0.8.x (المراجعة الهندسية 2026-11-24): كل اختبار
 * يثبت سلوك الإصلاح — وكل واحد منها كان يفشل قبل إصلاحه (قابل للتحقق بعكس
 * الإصلاح). ملفات حقيقية مؤقتة لا محاكاة.
 */
class HardeningRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    // ---------- 1. الاستئناف لا يعيد تنفيذ عملية منتهية ----------

    /** بناء سجل عملية جزئية كما تتركه موت العملية: RUNNING مع عناصر بحالاتها. */
    private fun interruptedCopyJournal(srcDir: Path, target: Path, fileStates: List<Pair<String, ItemState>>): Pair<OpJournal, String> {
        val jdir = dir("journal-int")
        val journal = FileOpJournal(jdir.resolve("ops"))
        val opId = FileOpEngine.newOpId()
        val record = OpRecord(
            id = opId, kind = OpKind.COPY, state = OpState.RUNNING,
            sources = fileStates.map { srcDir.resolve(it.first).toString() },
            targetDir = target.toString(),
            createdAt = System.currentTimeMillis()
        )
        val items = fileStates.mapIndexed { i, (name, state) ->
            OpItemRecord(
                opId = opId, index = i, group = i, itemKind = OpItemKind.FILE,
                state = state, src = srcDir.resolve(name).toString(),
                dst = target.resolve(name).toString(), size = 1
            )
        }
        journal.startOp(record, items)
        return journal to opId
    }

    @Test
    fun resumeRejectsTerminalStateOperation() {
        val d = dir("terminal")
        val src = dir("terminal-src")
        write(src.resolve("a.txt"), "a")
        val jdir = d.resolve("ops")
        val journal = FileOpJournal(jdir)
        val engine = FileOpEngine(journal)
        val opId = FileOpEngine.newOpId()
        runBlocking { engine.copy(listOf(src.resolve("a.txt")), d, ConflictPolicy.SKIP, opId) }
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        assertEquals(1, countFinishLines(jdir))

        try {
            runBlocking { engine.resume(opId, ConflictPolicy.SKIP) }
            fail("resume of a FINISHED operation must be refused")
        } catch (e: java.io.IOException) {
            assertTrue("refusal must be explicit: ${e.message}", e.message!!.contains(CoreOpCode.OP_ALREADY_FINISHED))
        }
        // الرفض لم يضف سطر إنهاء ثانياً ولم يغير الحالة.
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
        assertEquals(1, countFinishLines(jdir))
    }

    private fun countFinishLines(jdir: Path): Int =
        Files.list(jdir).use { stream ->
            stream.flatMap { f -> runCatching { Files.readAllLines(f) }.getOrDefault(emptyList()).stream() }
                .filter { it.startsWith("F\t") }.count().toInt()
        }

    @Test
    fun doubleResumeOfRunningOpExecutesPendingOnlyOnce() {
        val srcDir = dir("resume-src")
        write(srcDir.resolve("done.txt"), "original")
        write(srcDir.resolve("p1.txt"), "content-1")
        write(srcDir.resolve("p2.txt"), "content-2")
        val target = dir("resume-dst")
        write(target.resolve("done.txt"), "original")
        val (journal, opId) = interruptedCopyJournal(
            srcDir, target,
            listOf("done.txt" to ItemState.DONE, "p1.txt" to ItemState.PENDING, "p2.txt" to ItemState.PENDING)
        )
        val engine = FileOpEngine(journal)
        val stats = runBlocking { engine.resume(opId, ConflictPolicy.SKIP) }
        assertEquals(3, stats.ok)
        assertEquals("original", Files.readString(target.resolve("done.txt")))
        assertEquals("content-1", Files.readString(target.resolve("p1.txt")))
        assertEquals("content-2", Files.readString(target.resolve("p2.txt")))

        // استئناف ثانٍ (نافذة سباق الحوار): مرفوض — لا إعادة تنفيذ.
        try {
            runBlocking { engine.resume(opId, ConflictPolicy.SKIP) }
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains(CoreOpCode.OP_ALREADY_FINISHED))
        }
        assertEquals(OpState.FINISHED, journal.op(opId)!!.state)
    }

    // ---------- 2. منظف المؤقتات لا يمسّ نسخة الاستبدال الاحتياطية ----------
    // (0.8.1: كانت تُجتاح مع المؤقتات فتضيع بيانات المستخدم عند موت
    //  العملية وسط استبدال — الآن المصير يحسمه ReplaceBackupRecovery وحده.)

    @Test
    fun replaceBackupPrefixIsProtectedFromTempJanitor() {
        val d = dir("janitor")
        val journal = FileOpJournal(d.resolve("ops"))
        val opId = FileOpEngine.newOpId()
        val dst = d.resolve("out.txt")
        journal.startOp(
            OpRecord(opId, OpKind.EXTRACT, OpState.RUNNING, listOf("a.zip"), d.toString(), 0L),
            listOf(
                OpItemRecord(opId, 0, OpItemKind.FILE, ItemState.RUNNING, "a.zip", dst.toString(), group = 0)
            )
        )
        val old = d.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        Files.write(old, ByteArray(8))
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 2 * 60 * 60 * 1000))
        val fresh = d.resolve(".fm-replace-backup-" + java.util.UUID.randomUUID())
        Files.write(fresh, ByteArray(8))
        // المنظف العام (الإقلاع): لا يمسّ نسخة الاستبدال مهما تقادمت —
        // بيانات مستخدم، لا مؤقتات.
        val removed = OpRecovery.cleanupStaleTemps(
            journal, minAgeMs = 60L * 60 * 1000, nowMillis = System.currentTimeMillis()
        )
        assertEquals(0, removed)
        assertTrue("stale .fm-replace-backup- must NOT be swept (user data)", Files.exists(old))
        assertTrue("fresh .fm-replace-backup- must be kept (live operation)", Files.exists(fresh))
        // اليتيمة (بلا أمر مسجّل) تُكتشف للعرض على المستخدم لا للحذف.
        val orphans = ReplaceBackupRecovery.orphanReplaceBackups(
            setOf(d), knownBackups = emptySet(),
            nowMillis = System.currentTimeMillis(), minAgeMs = 0L
        )
        assertEquals(setOf(old, fresh), orphans.toSet())
    }

    // ---------- 3. ZipRewriter.extractOne: حدود القنبلة المضغوطة ----------

    private fun zip(path: Path, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(c); z.closeEntry()
            }
        }
    }

    @Test
    fun extractOneRejectsExpansionBombAndLeavesNoTemp() {
        val d = dir("bomb")
        val archive = d.resolve("bomb.zip")
        zip(archive, "huge.bin" to ByteArray(20 * 1024 * 1024)) // أصفار: ضغط ~1000:1
        val dest = d.resolve("out/huge.bin")
        try {
            ZipRewriter.extractOne(archive, "huge.bin", dest)
            fail("expansion bomb must be refused")
        } catch (e: java.io.IOException) {
            assertTrue(
                "explicit limit expected: ${e.message}",
                e.message!!.contains("expansion ratio") || e.message!!.contains("expands beyond")
            )
        }
        assertFalse("no partial destination file", Files.exists(dest))
        val leftovers = Files.list(d.resolve("out")).use { s -> s?.anyMatch { it.fileName.toString().startsWith(".fm-view-") } ?: false }
        assertFalse("no temp leftovers after refusal", leftovers)
    }

    @Test
    fun extractOneCopiesExactContentAtomically() {
        val d = dir("one")
        val archive = d.resolve("one.zip")
        val payload = "OpenFileManager".toByteArray(Charsets.ISO_8859_1)
        zip(archive, "note.txt" to payload)
        val dest = d.resolve("view/note.txt")
        ZipRewriter.extractOne(archive, "note.txt", dest)
        assertEquals("OpenFileManager", Files.readString(dest))
        val leftovers = Files.list(d.resolve("view")).use { s -> s?.anyMatch { it.fileName.toString().startsWith(".fm-view-") } ?: false }
        assertFalse(leftovers)
    }

    // ---------- 4. ميزانية PDF المركزية ----------

    /** يبني PDF صحيحاً بأجسام خام مع xref سليم وجذر معطى. */
    private fun buildPdf(rootNum: Int, objects: List<Pair<Int, ByteArray>>): ByteArray {
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
        w("trailer\n<< /Size ${maxNum + 1} /Root $rootNum 0 R >>\n")
        w("startxref\n$xrefStart\n%%EOF\n")
        return out.toByteArray()
    }

    private fun streamObj(dict: String, data: ByteArray): ByteArray =
        "<< $dict /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) +
            data + "\nendstream".toByteArray(Charsets.ISO_8859_1)

    private fun openPdf(budget: PdfResourceBudget, vararg objects: Pair<Int, ByteArray>): PdfFile =
        PdfFile(ByteBuffer.wrap(buildPdf(objects.maxOf { it.first }, objects.toList())), budget)

    private fun contentStream(file: PdfFile, num: Int): PdfStream = file.obj(num) as PdfStream

    /** كان قاموساً بـ 30 بايت يطلب مصفوفي 268 م.ب لكل منهما (OOM من ملف 4 ك.ب). */
    @Test(timeout = 20_000)
    fun predictorRowBombRefusedBeforeAllocation() {
        val parms = "/Predictor 12 /Columns 268435456 /Colors 1 /BitsPerComponent 8"
        val raw = ByteArray(64)
        val data = ByteArrayOutputStream().let { bos ->
            val def = java.util.zip.Deflater()
            def.setInput(raw); def.finish()
            val buf = ByteArray(8192)
            while (!def.finished()) bos.write(buf, 0, def.deflate(buf))
            def.end(); bos.toByteArray()
        }
        val file = openPdf(
            PdfResourceBudget(),
            1 to streamObj("/Filter /FlateDecode /DecodeParms << $parms >>", data),
            2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1)
        )
        try {
            file.decode(contentStream(file, 1))
            fail("row bomb must be refused before allocation")
        } catch (e: PdfFormatException) {
            assertEquals("predictor row too large (268435456 bytes)", e.message)
        }
    }

    @Test
    fun filterChainLengthIsCapped() {
        val filters = (1..9).joinToString(" ") { "/FlateDecode" }
        val data = ByteArray(8)
        val file = openPdf(
            PdfResourceBudget(),
            1 to streamObj("/Filter [$filters]", data),
            2 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1)
        )
        try {
            file.decode(contentStream(file, 1))
            fail()
        } catch (e: PdfFormatException) {
            assertEquals("filter chain too long (9 > 8)", e.message)
        }
    }

    @Test
    fun decodeBudgetChargesDistinctStreamsOnceAndCapsTotal() {
        val budget = PdfResourceBudget(maxTotalStreamBytes = 3_000)
        val a = "A".repeat(2_000).toByteArray(Charsets.ISO_8859_1)
        val b = "B".repeat(2_000).toByteArray(Charsets.ISO_8859_1)
        val file = openPdf(
            budget,
            1 to streamObj("", a),
            2 to streamObj("", b),
            3 to "<< /Type /Catalog >>".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals(2000, file.decode(contentStream(file, 1)).size)
        // إعادة فك نفس التيار: لا إعادة احتساب (قراءة صفحة مرة أخرى لا تُنهي مستنداً كبيراً).
        assertEquals(2000, file.decode(contentStream(file, 1)).size)
        // تيار مختلف: يتجاوز الميزانية → رفض صريح.
        try {
            file.decode(contentStream(file, 2))
            fail()
        } catch (e: PdfBudgetException) {
            assertTrue(e.message!!.contains("budget"))
        }
    }

    /** صفحة بعدائية بمصفوفة /Contents مداخلها سليمة كل واحد لكن مجموعها فوق السقف. */
    @Test
    fun pageContentBudgetCapsMultiStreamContents() {
        val budget = PdfResourceBudget(maxPageContentBytes = 1_500)
        val c1 = "BT (1) Tj ET ".toByteArray(Charsets.ISO_8859_1) + ByteArray(1_100) { 'x'.code.toByte() }
        val c2 = "BT (2) Tj ET ".toByteArray(Charsets.ISO_8859_1) + ByteArray(1_100) { 'y'.code.toByte() }
        val pdf = buildPdf(
            5,
            listOf(
                1 to streamObj("", c1),
                2 to streamObj("", c2),
                3 to "<< /Type /Page /Parent 4 0 R /MediaBox [0 0 100 100] /Contents [1 0 R 2 0 R] >>".toByteArray(Charsets.ISO_8859_1),
                4 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.ISO_8859_1),
                5 to "<< /Type /Catalog /Pages 4 0 R >>".toByteArray(Charsets.ISO_8859_1)
            )
        )
        val doc = PdfTextDocument.open(pdf, budget)
        try {
            doc.pageText(0)
            fail("aggregated page content above the budget must throw")
        } catch (e: PdfBudgetException) {
            assertTrue(e.message!!.contains("page content too large"))
        }
    }

    /** مصفوفة TJ واحدة ضخمة: سقف أحرف الصفحة يسري على مسار الالتحاق أيضاً. */
    @Test(timeout = 30_000)
    fun tjArrayCharCapHoldsOnContinuationPath() {
        // 80000 عنصر × 10 أحرف = 800 ألف حرف > 600 ألف: قبل الإصلاح كان sb
        // يُلحق بها كلها بلا فحص (مسار الالتحاق) فيتجاوز الحد المعلن.
        val sb = StringBuilder("BT /F1 12 Tf [")
        repeat(80_000) {
            sb.append("(AAAAAAAAAA) -100 ")
        }
        sb.append("] TJ ET")
        val content = sb.toString().toByteArray(Charsets.ISO_8859_1)
        val pdf = buildPdf(
            5,
            listOf(
                1 to streamObj("", content),
                2 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".toByteArray(Charsets.ISO_8859_1),
                3 to "<< /Type /Page /Parent 4 0 R /MediaBox [0 0 100 100] /Resources << /Font << /F1 2 0 R >> >> /Contents 1 0 R >>".toByteArray(Charsets.ISO_8859_1),
                4 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.ISO_8859_1),
                5 to "<< /Type /Catalog /Pages 4 0 R >>".toByteArray(Charsets.ISO_8859_1)
            )
        )
        val text = PdfTextDocument.open(pdf).pageText(0).text
        // الحد 600 ألف: السلوك غير المقيّد كان يلحق 800 ألف حرفاً كاملة.
        assertTrue("cap is ~600k, got ${text.length}", text.length in 590_000..600_020)
    }

    /**
     * قنبلة ToUnicode: 3000 مدخل bfrange يغطي كل النطاق (0..FFFF) بصيغة
     * السلسلة — قبل الإصلاح: 3000×65536 = 196 مليون إدخال خريطة (GC/ذاكرة)؛
     * بعد الإصلاح: الخريطة سقفها 65536 فينتهي فوراً ويعمل الاستخراج.
     */
    @Test(timeout = 30_000)
    fun toUnicodeFullRangeBombIsBoundedAndStillMaps() {
        val tu = buildString {
            append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
            append("1 begincodespacerange\n<0000> <ffff>\nendcodespacerange\n")
            append("3000 beginbfrange\n")
            repeat(3000) { append("<0000> <ffff> <0041>\n") }
            append("endbfrange\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val content = "BT /F1 12 Tf <0042> Tj ET".toByteArray(Charsets.ISO_8859_1)
        val pdf = buildPdf(
            7,
            listOf(
                1 to streamObj("", content),
                2 to streamObj("", tu), // ToUnicode
                3 to "<< /Type /Font /Subtype /Type0 /BaseFont /T /Encoding /Identity-H /ToUnicode 2 0 R /DescendantFonts [6 0 R] >>".toByteArray(Charsets.ISO_8859_1),
                4 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".toByteArray(Charsets.ISO_8859_1),
                5 to "<< /Type /Page /Parent 8 0 R /MediaBox [0 0 100 100] /Resources << /Font << /F1 3 0 R >> >> /Contents 1 0 R >>".toByteArray(Charsets.ISO_8859_1),
                6 to "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /T /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 500 >>".toByteArray(Charsets.ISO_8859_1),
                7 to "<< /Type /Catalog /Pages 8 0 R >>".toByteArray(Charsets.ISO_8859_1),
                8 to "<< /Type /Pages /Kids [5 0 R] /Count 1 >>".toByteArray(Charsets.ISO_8859_1)
            )
        )
        val pageText = PdfTextDocument.open(pdf).pageText(0)
        // التعيين يعمل (حرف واحد من كود 0x0042) والتفكيك انتهى في الحدود.
        assertEquals(1, pageText.text.length)
    }

    // ---------- 5. تعمية المسار ----------

    @Test
    fun redactHidesSingleComponentAbsolutePaths() {
        val msg = "failed to stat /sdcard and open /private.pdf again"
        val redacted = PathRedactor.redact(msg)
        assertFalse("leaked /sdcard", redacted.contains("sdcard"))
        assertFalse("leaked /private.pdf", redacted.contains("private.pdf"))
        assertTrue("tokens must be substituted", PathRedactor.containsNoRawPath(redacted))
    }
}
