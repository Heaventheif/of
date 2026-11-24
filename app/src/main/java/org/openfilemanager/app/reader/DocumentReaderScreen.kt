package org.openfilemanager.app.reader

import androidx.compose.runtime.Composable

/** Entry point of the built-in reader: picks the engine by extension (PDF or DOCX only; see [ReaderRouting]). */
@Composable
fun DocumentReaderScreen(path: String, onBack: () -> Unit) {
    when (ReaderRouting.formatOf(path)) {
        ReaderFormat.PDF -> PdfReaderScreen(path, onBack)
        ReaderFormat.DOCX -> DocxReaderScreen(path, onBack)
        null -> onBack()
    }
}
