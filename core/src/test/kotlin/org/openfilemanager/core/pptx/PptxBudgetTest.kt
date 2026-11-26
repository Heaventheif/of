package org.openfilemanager.core.pptx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PptxBudgetTest {
    @get:Rule val tmp = TemporaryFolder()

    /** سقف الميزانية الجديد (مطابق لثابت القارئ): 512 MiB. */
    private val MAX_TOTAL_XML_BYTES = 512L * 1024 * 1024

    private fun slideXmlOfSize(size: Long): String {
        val head = "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
            "<p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id=\"1\" name=\""
        val tail = "\"/></p:nvSpPr><p:spPr/></p:sp></p:spTree></p:cSld></p:sld>"
        val pad = size - head.length - tail.length
        require(pad >= 0) { "size $size too small for template" }
        return head + "x".repeat(pad.toInt()) + tail
    }

    private fun relsXmlOfSize(size: Long, target: String): String {
        val head = "<Relationships><Relationship Id=\"rId1\" " +
            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide\" " +
            "Target=\"$target\" pad=\""
        val tail = "\"/></Relationships>"
        val pad = size - head.length - tail.length
        require(pad >= 0) { "size $size too small for rels template" }
        return head + "x".repeat(pad.toInt()) + tail
    }

    /** يبني الحاوية بالتدفق: (اسم الجزء، حجمه المفكوك بالبايت بالضبط). */
    private fun deck(name: String, parts: List<Pair<String, Long>>): File =
        File(tmp.root, name).also { f ->
            ZipOutputStream(FileOutputStream(f)).use { z ->
                z.putNextEntry(ZipEntry("[Content_Types].xml"))
                z.write("""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray())
                z.closeEntry()
                for ((part, size) in parts) {
                    val xml = if (part.endsWith(".rels")) relsXmlOfSize(size, "../notesSlides/notesSlide1.xml")
                    else slideXmlOfSize(size)
                    z.putNextEntry(ZipEntry(part))
                    z.write(xml.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
            }
        }

    private fun expectTooLarge(f: File) {
        try {
            PptxReader.parse(f)
            fail("expected PptxException.TOO_LARGE")
        } catch (e: PptxException) {
            assertEquals(PptxException.Reason.TOO_LARGE, e.reason)
        }
    }

    private fun slidePart(i: Int) = "ppt/slides/slide$i.xml"
    private fun slideRels(i: Int) = "ppt/slides/_rels/slide$i.xml.rels"
    private val MIB = 1024L * 1024

    // ---- أجزاء صغيرة فردياً (<24MB) مجموعها فوق الميزانية: TOO_LARGE ----
    @Test
    fun partsIndividuallyUnderPerPartCapButCumulativelyOverBudgetThrowTooLarge() {
        // 24 جزءاً × 23MiB = 552MiB > 512MiB، وكل جزء تحت حد 24MB الفردي:
        // يرمي TOO_LARGE عند قراءة الجزء 23 (المجموع يتجاوز عندئذ).
        val perPart = 23L * MIB
        expectTooLarge(deck("over.pptx", (1..24).map { slidePart(it) to perPart }))
    }

    // ---- عند الميزانية بالضبط: يمر؛ +1 بايت: TOO_LARGE ----
    @Test
    fun exactlyAtCumulativeBudgetParses() {
        // 23 جزءاً × 23,000,000 + جزء أخير = 536,870,912 بالضبط (512MiB):
        // الشرط «أكبر من» صارم فلا يرمي عند التساوي.
        val base = 23_000_000L
        val twentyThree = 23 * base
        val last = MAX_TOTAL_XML_BYTES - twentyThree // 7,870,912 ≤ 24MB
        val f = deck(
            "exact.pptx",
            (1..23).map { slidePart(it) to base } + listOf(slidePart(24) to last)
        )
        val presentation = PptxReader.parse(f)
        assertEquals(24, presentation.slides.size)
    }

    @Test
    fun oneByteOverCumulativeBudgetThrowsTooLarge() {
        val base = 23_000_000L
        val twentyThree = 23 * base
        val last = MAX_TOTAL_XML_BYTES - twentyThree + 1
        val f = deck(
            "over1.pptx",
            (1..23).map { slidePart(it) to base } + listOf(slidePart(24) to last)
        )
        expectTooLarge(f)
    }

    // ---- أجزاء العلاقات تُحتسب ضمن الميزانية ----
    @Test
    fun relationshipPartsCountTowardCumulativeBudget() {
        // 22 شريحة × 23MiB + شريحة أخيرة صغيرة = 531.5MiB تحت السقف، ثم
        // علاقات الشريحة الأخيرة 9MiB تدفع المجموع فوق السقف أثناء قراءة
        // العلاقات نفسها. لو لم تُحتسب العلاقات لاتمّ التحليل بنجاح.
        val perPart = 23L * MIB
        val parts = (1..22).map { slidePart(it) to perPart } +
            listOf(slidePart(23) to 1_000_000L) +
            listOf(slideRels(23) to (9L * MIB))
        expectTooLarge(deck("rels.pptx", parts))
    }

    // ---- أجزاء الملاحظات تُحتسب ضمن الميزانية ----
    @Test
    fun notesPartsCountTowardCumulativeBudget() {
        // المجموع نفسه يقفز فوق السقف عند قراءة جزء الملاحظات بعد الشرائح
        // وعلاقاتها — إثبات أن بايتات الملاحظات تُحتسب لا تُهمل.
        val perPart = 23L * MIB
        val parts = (1..22).map { slidePart(it) to perPart } +
            listOf(
                slidePart(23) to 1_000_000L,
                slideRels(23) to 1_000L,
                "ppt/notesSlides/notesSlide1.xml" to (9L * MIB)
            )
        expectTooLarge(deck("notes.pptx", parts))
    }

    // ---- عرض عادي صغير يمر كما كان (لا انحدار) ----
    @Test
    fun normalSmallDeckStillParses() {
        val slide = """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
            <p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
            <p:txBody><a:bodyPr/><a:p><a:r><a:t>Hello budget</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        val f = File(tmp.root, "small.pptx").also { out ->
            ZipOutputStream(FileOutputStream(out)).use { z ->
                z.putNextEntry(ZipEntry("[Content_Types].xml"))
                z.write("""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray())
                z.closeEntry()
                for (i in 1..3) {
                    z.putNextEntry(ZipEntry(slidePart(i)))
                    z.write(slide.toByteArray())
                    z.closeEntry()
                }
            }
        }
        val presentation = PptxReader.parse(f)
        assertEquals(3, presentation.slides.size)
        assertEquals("Hello budget", presentation.slides[0].title)
        assertTrue(presentation.find("hello").isNotEmpty())
    }

    // ---- سقف النص التراكمي القديم يبقى كما هو (الجداول محسوبة فيه) ----
    @Test
    fun existingTextBudgetStillAppliesToTableCells() {
        // جدول موزع على شريحتين (كل جزء ≤ 24MB الفردي) بمحتوى نصي 42M حرفاً
        // > سقف 40M حرفاً القديم: TOO_LARGE عبر المسار القديم غير المعدّل
        // (الشرط budget.text > MAX_TOTAL_CHARS) لا عبر حد الجزء الجديد.
        fun tableCellSlide(chars: Int) =
            "<p:graphicFrame><a:graphic><a:tbl><a:tr h=\"1\"><a:tc><a:txBody><a:p><a:r><a:t>" +
                "Q".repeat(chars) +
                "</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></a:graphic></p:graphicFrame>"
        fun slideWith(body: String) = """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
            <p:cSld><p:spTree>$body</p:spTree></p:cSld></p:sld>"""
        val f = File(tmp.root, "bigtext.pptx").also { out ->
            ZipOutputStream(FileOutputStream(out)).use { z ->
                z.putNextEntry(ZipEntry("[Content_Types].xml"))
                z.write("""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray())
                z.closeEntry()
                for (i in 1..2) {
                    z.putNextEntry(ZipEntry(slidePart(i)))
                    z.write(slideWith(tableCellSlide(21_000_000)).toByteArray())
                    z.closeEntry()
                }
            }
        }
        expectTooLarge(f)
    }
}
