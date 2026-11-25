package org.openfilemanager.app.reader

import java.io.File

/** Which built-in document reader (if any) handles a file. PDF, DOCX and PPTX; everything else keeps its existing route. */
object ReaderRouting {
    fun formatOf(name: String): ReaderFormat? = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> ReaderFormat.PDF
        "docx" -> ReaderFormat.DOCX
        else -> null
    }

    /** Legacy .doc: opened by the text-only viewer. */
    /** PowerPoint .pptx: opened by the built-in slide reader (text, tables and speaker notes). */
    fun isPresentation(name: String): Boolean = name.substringAfterLast('.', "").equals("pptx", ignoreCase = true)

    fun isLegacyDoc(name: String): Boolean = name.substringAfterLast('.', "").equals("doc", ignoreCase = true)

    fun formatOf(file: File): ReaderFormat? = formatOf(file.name)
}
