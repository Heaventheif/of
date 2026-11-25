package org.openfilemanager.core.pptx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** متانة المحلّل XML الداخلي وقراءة ملف PowerPoint حقيقي وُلِّد ببرنامج فعلي (python-pptx). */
class PptxRobustnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- MiniXml --------------------------------------------------------------------------------------------

    @Test
    fun attributesMayContainQuotedAngleBrackets() {
        val n = MiniXml.parse("""<root><node t="a>b" c='x&lt;y' d="1"/></root>""").children[0].children[0]
        assertEquals("a>b", n.attr("t"))
        assertEquals("x<y", n.attr("c"))
        assertEquals("1", n.attr("d"))
    }

    @Test
    fun mismatchedCloseTagsDoNotBreakTheTree() {
        val root = MiniXml.parse("<a><b>text</a></b><c/>")
        val a = root.children[0]
        assertEquals(listOf("b"), a.children.map { it.name })
        assertEquals("text", a.children[0].text.toString())
        assertEquals("c", root.children[1].name)
    }

    @Test
    fun decodesNumericAndNamedEntities() {
        val t = MiniXml.parse("<t>&#x41;&#66;&amp;&lt;&gt;&quot;&apos;&nbsp;&mdash;</t>").children[0].text.toString()
        assertEquals("AB&<>\"'\u00A0—", t)
    }

    @Test
    fun commentsAndProcessingInstructionsAreIgnored() {
        val root = MiniXml.parse("<?xml version=\"1.0\"?><!-- <fake attr=\"x\"> --><a>ok<!--</a>--></a>")
        assertEquals(1, root.children.size)
        assertEquals("ok", root.children[0].text.toString())
    }

    @Test
    fun excessiveNestingIsRejectedAsCorrupt() {
        try {
            MiniXml.parse("<a>" + "<b>".repeat(400) + "x" + "</b>".repeat(400) + "</a>")
            fail("deep nesting should be rejected")
        } catch (e: PptxException) {
            assertEquals(PptxException.Reason.CORRUPT, e.reason)
        }
    }

    // ---- ملف حقيقي -------------------------------------------------------------------------------------------

    private fun resourceFile(name: String): File {
        val f = File(tmp.root, name)
        javaClass.getResourceAsStream("/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun parsesARealPowerPointFile() {
        val deck = PptxReader.parse(resourceFile("real.pptx"))
        assertEquals(3, deck.slides.size)
        assertEquals("Quarterly Review", deck.slides[0].title)
        assertEquals("Agenda", deck.slides[1].title)
        val paras = deck.slides[1].blocks.filterIsInstance<SlideBlock.Text>().first().paragraphs
        assertEquals(listOf("Revenue by region", "EMEA details", "APAC details", "Next steps"), paras.map { it.text })
        assertEquals(listOf(0, 1, 1, 0), paras.map { it.level })
        val table = deck.slides[2].blocks.filterIsInstance<SlideBlock.Table>().first()
        assertEquals(listOf(listOf("Quarter", "Revenue", "Δ%"), listOf("Q1", "1.2M", "+8%"), listOf("Q2", "1.4M", "+17%")), table.rows)
        assertEquals("Highlight the Q2 jump.", deck.slides[2].notes)
        assertEquals(listOf(2), deck.find("q2"))
        assertTrue(deck.slides.none { it.hidden })
    }
}
