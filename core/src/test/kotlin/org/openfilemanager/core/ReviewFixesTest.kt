package org.openfilemanager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression tests for the 0.7.9 code-review fixes. */
class ReviewFixesTest {
    @Test
    fun zipEntryNameRejectsBackslashConsistently() {
        assertEquals(NameProblem.FORBIDDEN_CHAR, ZipModel.entryNameProblem("a\\b.txt"))
        assertNull(ZipModel.entryNameProblem("a b.txt"))
    }
}
