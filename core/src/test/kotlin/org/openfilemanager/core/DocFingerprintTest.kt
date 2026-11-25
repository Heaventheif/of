package org.openfilemanager.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DocFingerprintTest {
    // Regression: files between 64KB and 128KB used to overflow the 64KB read buffer (docx failed to open).
    @Test fun midSizeFileDoesNotThrow() {
        val f = File.createTempFile("fp_", ".bin")
        try {
            f.writeBytes(ByteArray(100 * 1024) { it.toByte() })
            assertEquals(32, DocFingerprint.of(f).length)
        } finally { f.delete() }
    }
}
