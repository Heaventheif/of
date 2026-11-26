package org.openfilemanager.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import org.openfilemanager.core.NameRules

/**
 * Exposes the app's storage roots to the system picker (DocumentsUI), so other apps can browse,
 * pick and save files through "Open from" / "Save to". Document id = canonical absolute path.
 * Every id is checked against the live roots and Android/data|obb is refused.
 */
class OfmDocumentsProvider : DocumentsProvider() {

    private val repo by lazy { StorageRepository(context!!) }

    private val blocked = Regex("/Android/(data|obb)(/|$)")

    private fun roots(): List<File> = repo.roots().map { it.path.toFile().canonicalFile }

    /** Resolves [docId] to a file inside a root; throws FileNotFoundException otherwise. */
    private fun fileFor(docId: String): File {
        val f = try { File(docId).canonicalFile } catch (e: java.io.IOException) {
            throw FileNotFoundException("Bad document id")
        }
        val inside = roots().any { r -> f == r || f.path.startsWith(r.path + File.separator) }
        if (!inside || blocked.containsMatchIn(f.path)) throw FileNotFoundException("Not available")
        return f
    }

    private fun idOf(f: File): String = f.canonicalPath

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        val ctx = context ?: return c
        repo.roots().forEach { root ->
            val dir = root.path.toFile().canonicalFile
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, idOf(dir))
                add(Root.COLUMN_DOCUMENT_ID, idOf(dir))
                add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name) + " — " + root.label)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_AVAILABLE_BYTES, dir.usableSpace)
            }
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLUMNS).also { addRow(it, fileFor(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = fileFor(parentDocumentId)
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        parent.listFiles()?.filterNot { blocked.containsMatchIn(it.path) }?.forEach { addRow(c, it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a folder")
        val safe = displayName.replace('/', '_').takeIf { NameRules.isValid(it, strictFat = false) }
            ?: throw FileNotFoundException("Invalid name")
        var target = File(parent, safe)
        var n = 1
        while (target.exists()) {
            val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
            target = File(parent, safe.substring(0, dot) + " ($n)" + safe.substring(dot)); n++
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!ok) throw FileNotFoundException("Create failed")
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        val f = fileFor(documentId)
        if (roots().contains(f)) throw FileNotFoundException("Cannot delete a root")
        if (!f.deleteRecursively()) throw FileNotFoundException("Delete failed")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val f = fileFor(documentId)
        if (roots().contains(f) || !NameRules.isValid(displayName, strictFat = false)) throw FileNotFoundException("Invalid")
        val target = File(f.parentFile, displayName)
        if (target.exists() || !f.renameTo(target)) throw FileNotFoundException("Rename failed")
        return idOf(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val p = fileFor(parentDocumentId); val c = fileFor(documentId)
        c.path.startsWith(p.path + File.separator)
    }.getOrDefault(false)

    private fun addRow(c: MatrixCursor, f: File) {
        val isDir = f.isDirectory
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = flags or if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            add(Document.COLUMN_DISPLAY_NAME, f.name.ifEmpty { f.path })
            add(Document.COLUMN_SIZE, if (isDir) null else f.length())
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(f))
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    private companion object {
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON,
            Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES
        )
        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )
    }
}
