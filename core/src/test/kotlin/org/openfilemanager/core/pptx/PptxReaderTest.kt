package org.openfilemanager.core.pptx

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PptxReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(name: String, parts: Map<String, String>): File =
        File(tmp.root, name).also { f ->
            ZipOutputStream(FileOutputStream(f)).use { z ->
                for ((n, c) in parts) {
                    z.putNextEntry(ZipEntry(n))
                    z.write(c.toByteArray())
                    z.closeEntry()
                }
            }
        }

    private fun slide(title: String?, body: String, extra: String = "", show: String? = null): String = """
        <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
               xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" ${if (show != null) "show=\"$show\"" else ""}>
          <p:cSld><p:spTree>
            ${if (title != null) """<p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
              <p:txBody><a:bodyPr/><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>""" else ""}
            $body
            $extra
          </p:spTree></p:cSld>
        </p:sld>
    """.trimIndent()

    private val contentTypes = """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>"""

    @Test
    fun readsOrderTitlesBulletsTablesAndNotes() {
        val body1 = """<p:sp><p:nvSpPr><p:nvPr><p:ph type="obj"/></p:nvPr></p:nvSpPr><p:txBody><a:bodyPr/>
            <a:p><a:pPr/><a:r><a:t>First point</a:t></a:r></a:p>
            <a:p><a:pPr lvl="1"/><a:r><a:t>Sub&nbsp;point</a:t></a:r></a:p>
            <a:p><a:pPr><a:buNone/></a:pPr><a:r><a:t>Plain line</a:t></a:r></a:p>
            <a:p><a:r><a:t>  </a:t></a:r></a:p>
            </p:txBody></p:sp>"""
        val table = """<p:graphicFrame><a:graphic><a:tbl>
            <a:tr h="1"><a:tc><a:txBody><a:p><a:r><a:t>Q1</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t>120</a:t></a:r></a:p></a:txBody></a:tc></a:tr>
            <a:tr h="1"><a:tc><a:txBody><a:p><a:r><a:t>Q2</a:t></a:r></a:p><a:p><a:r><a:t>note</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t/></a:r></a:p></a:txBody></a:tc></a:tr>
            </a:tbl></a:graphic></p:graphicFrame>"""
        val footer = """<p:sp><p:nvSpPr><p:nvPr><p:ph type="ftr"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Confidential</a:t></a:r></a:p></p:txBody></p:sp>"""
        val f = zip(
            "deck.pptx", mapOf(
                "[Content_Types].xml" to contentTypes,
                "ppt/presentation.xml" to """<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst>
                    <p:sldId id="256" r:id="rId3"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst></p:presentation>""",
                "ppt/_rels/presentation.xml.rels" to """<Relationships>
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                    <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
                    </Relationships>""",
                "ppt/slides/slide1.xml" to slide("Agenda", body1, footer),
                "ppt/slides/slide2.xml" to slide("Results", table, show = "0"),
                "ppt/slides/_rels/slide2.xml.rels" to """<Relationships>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide" Target="../notesSlides/notesSlide2.xml"/>
                    <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" TargetMode="External" Target="https://example.com"/>
                    </Relationships>""",
                "ppt/notesSlides/notesSlide2.xml" to slide(
                    null,
                    """<p:sp><p:nvSpPr><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>2</a:t></a:r></a:p></p:txBody></p:sp>
                       <p:sp><p:nvSpPr><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Say it slowly</a:t></a:r></a:p></p:txBody></p:sp>"""
                )
            )
        )
        val deck = PptxReader.parse(f)
        assertEquals(2, deck.slides.size)
        // الترتيب من قائمة الشرائح لا من أرقام الملفات
        assertEquals("Results", deck.slides[0].title)
        assertEquals(1, deck.slides[0].number)
        assertTrue(deck.slides[0].hidden)
        assertEquals("Say it slowly", deck.slides[0].notes)
        val rows = (deck.slides[0].blocks[0] as SlideBlock.Table).rows
        assertEquals(listOf(listOf("Q1", "120"), listOf("Q2\nnote", "")), rows)

        val agenda = deck.slides[1]
        assertEquals("Agenda", agenda.title)
        assertFalse(agenda.hidden)
        assertEquals("", agenda.notes)
        // التذييل ليس محتوى
        assertEquals(1, agenda.blocks.size)
        val paras = (agenda.blocks[0] as SlideBlock.Text).paragraphs
        assertEquals(listOf("First point", "Sub point", "Plain line"), paras.map { it.text })
        assertEquals(listOf(0, 1, 0), paras.map { it.level })
        assertEquals(listOf(true, true, false), paras.map { it.bullet })

        assertEquals(listOf(0), deck.find("q1"))
        assertEquals(listOf(1), deck.find("GEN"))
        assertEquals(listOf(0), deck.find("SLOWLY"))
        assertTrue(deck.find("zzz").isEmpty())
    }

    @Test
    fun readsGroupedShapesBreaksAndCdata() {
        val grouped = """<p:grpSp><p:sp><p:nvSpPr><p:nvPr><p:ph/></p:nvPr></p:nvSpPr><p:txBody><a:p>
            <a:r><a:t><![CDATA[A < B & "C"]]></a:t></a:r><a:br/><a:r><a:t>after break</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Group title</a:t></a:r></a:p></p:txBody></p:sp></p:grpSp>"""
        val f = zip(
            "g.pptx", mapOf(
                "[Content_Types].xml" to contentTypes,
                "ppt/slides/slide1.xml" to slide(null, grouped),
                "ppt/slides/slide2.xml" to slide("Second", "", extra = "<!-- comment <p:sp/> --><p:sp><p:txBody><a:p><a:r><a:t>x</a:t></a:r></a:p></p:txBody></p:sp>")
            )
        )
        val deck = PptxReader.parse(f)
        assertEquals(2, deck.slides.size)
        assertEquals("Group title", deck.slides[0].title)
        assertEquals("A < B & \"C\"\nafter break", (deck.slides[0].blocks[0] as SlideBlock.Text).paragraphs[0].text)
        assertEquals("", deck.slides[0].notes)
        assertEquals("x", (deck.slides[1].blocks[0] as SlideBlock.Text).paragraphs[0].text)
    }

    @Test
    fun reportsWhyAFileCannotBeOpened() {
        val cases = listOf(
            Triple("notzip.pptx", "hello world".toByteArray(), PptxException.Reason.NOT_OOXML),
            // OLE بلا مجرى EncryptedPackage: ملف قديم أُعطي امتداد pptx — ليس مشفراً
            Triple("ole.pptx", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()), PptxException.Reason.NOT_OOXML),
            // OLE يحمل EncryptedPackage: عرض OOXML مشفر بكلمة مرور
            Triple(
                "encrypted.pptx",
                byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) +
                    "EncryptedPackage".toByteArray(Charsets.UTF_16LE),
                PptxException.Reason.ENCRYPTED
            ),
            Triple("plain.zip", null, PptxException.Reason.NOT_OOXML)
        )
        for ((name, raw, reason) in cases) {
            val f = File(tmp.root, name)
            if (raw != null) f.writeBytes(raw)
            else ZipOutputStream(FileOutputStream(f)).use { it.putNextEntry(ZipEntry("a.txt")); it.write(1); it.closeEntry() }
            try { PptxReader.parse(f); fail("$name should have failed") } catch (e: PptxException) { assertEquals(name, reason, e.reason) }
        }
        val empty = zip("empty.pptx", mapOf("[Content_Types].xml" to contentTypes, "docProps/app.xml" to "<a/>"))
        try { PptxReader.parse(empty); fail("no slides should fail") } catch (e: PptxException) { assertEquals(PptxException.Reason.EMPTY, e.reason) }
    }
}
