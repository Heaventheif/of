package org.openfilemanager.app.reader

import java.io.File

/** Which built-in document reader (if any) handles a file. Only PDF and DOCX: everything else keeps its existing route. */
object ReaderRouting {
    fun formatOf(name: String): ReaderFormat? = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> ReaderFormat.PDF
        "docx" -> ReaderFormat.DOCX
        else -> null
    }

    fun formatOf(file: File): ReaderFormat? = formatOf(file.name)
}
