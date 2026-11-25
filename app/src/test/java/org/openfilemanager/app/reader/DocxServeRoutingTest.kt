package org.openfilemanager.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** (مراجعة 0.8.6) قائمة السماح للويب فيو: قرار التوجيه كله دالة خالصة تُختبر دون WebView. */
class DocxServeRoutingTest {

    private val host = DocxReaderState.HOST

    @Test
    fun servesOnlyTheFourKnownAssetsAndTheDocument() {
        assertEquals(DocxReaderState.Res.HTML, DocxReaderState.route("GET", "https", host, "/reader.html"))
        assertEquals(DocxReaderState.Res.JS, DocxReaderState.route("GET", "https", host, "/reader.js"))
        assertEquals(DocxReaderState.Res.CSS, DocxReaderState.route("GET", "https", host, "/reader.css"))
        assertEquals(DocxReaderState.Res.MAMMOTH, DocxReaderState.route("GET", "https", host, "/mammoth.min.js"))
        assertEquals(DocxReaderState.Res.DOC, DocxReaderState.route("GET", "https", host, "/doc.docx"))
    }

    @Test
    fun blocksWrongMethodSchemeHostAndUnknownPaths() {
        assertNull(DocxReaderState.route("POST", "https", host, "/reader.html"))
        assertNull(DocxReaderState.route("GET", "http", host, "/reader.html"))
        assertNull(DocxReaderState.route("GET", "https", "evil.example", "/reader.html"))
        assertNull(DocxReaderState.route("GET", "https", host, "/etc/passwd"))
        assertNull(DocxReaderState.route("GET", "https", host, null))
        assertNull(DocxReaderState.route("GET", "file", host, "/reader.html"))
    }

    @Test
    fun pathAndHostLookalikesDoNotPass() {
        assertNull(DocxReaderState.route("GET", "https", "${host}.evil.example", "/reader.html"))
        assertNull(DocxReaderState.route("GET", "https", host, "/reader.html?x=1"))
        assertNull(DocxReaderState.route("GET", "https", host, "/READER.html"))
        assertNull(DocxReaderState.route("GET", "https", host, "//reader.html"))
    }

    @Test
    fun documentMimeMatchesOoxmlWordprocessing() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            DocxReaderState.Res.DOC.mime
        )
    }
}
