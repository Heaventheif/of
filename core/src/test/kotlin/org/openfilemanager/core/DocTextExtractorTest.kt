package org.openfilemanager.core

import org.junit.Assert.assertTrue
import org.junit.Test

class DocTextExtractorTest {
    @Test fun extractsTextFromSampleDoc() {
        val bytes = javaClass.getResourceAsStream("/sample.doc")!!.readBytes()
        val t = DocTextExtractor.extract(bytes)
        assertTrue(t, t.contains("Title"))
        assertTrue(t, t.contains("مرحبا بالعالم Hello world"))
        assertTrue(t, t.contains("é ñ"))
        assertTrue(t, t.contains("c00") && t.contains("c11"))
        assertTrue(t.contains("X".repeat(1000)))
    }

    @Test(expected = DocFormatException::class) fun rejectsNonOle() {
        DocTextExtractor.extract(ByteArray(1024))
    }
}
