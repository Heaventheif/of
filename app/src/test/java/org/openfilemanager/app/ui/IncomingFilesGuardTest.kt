package org.openfilemanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (مراجعة 0.8.6-#1) بوابة file:// في الاستقبال: مخازن المشاركة فقط، ولا خاصية للتطبيق. */
class IncomingFilesGuardTest {

    private val privateRoots = listOf(
        "/data/user/0/org.openfilemanager.app",
        "/data/user/0/org.openfilemanager.app/cache",
        "/data/user/0/org.openfilemanager.app/files",
        "/storage/emulated/0/Android/data/org.openfilemanager.app"
    )

    @Test
    fun privatePathsAreRejected() {
        assertTrue(IncomingFiles.isPrivatePath("/data/user/0/org.openfilemanager.app", privateRoots))
        assertTrue(IncomingFiles.isPrivatePath("/data/user/0/org.openfilemanager.app/cache/incoming_x/f.docx", privateRoots))
        assertTrue(IncomingFiles.isPrivatePath("/data/user/0/org.openfilemanager.app/files/settings.json", privateRoots))
    }

    @Test
    fun privateRootBoundaryIsNotAPrefixTrap() {
        // شقيقة الجذر الخاص ليست خاصة: app2 تطبيق آخر
        assertFalse(IncomingFiles.isPrivatePath("/data/user/0/org.openfilemanager.app2/cache/x", privateRoots))
    }

    @Test
    fun sharedStorageOutsidePrivateDirsIsAllowed() {
        assertFalse(IncomingFiles.isPrivatePath("/storage/emulated/0/DCIM/photo.jpg", privateRoots))
        assertFalse(IncomingFiles.isPrivatePath("/storage/0000-1111/DCIM/clip.mp4", privateRoots))
    }

    @Test
    fun appExternalDirsArePrivate() {
        assertTrue(IncomingFiles.isPrivatePath("/storage/emulated/0/Android/data/org.openfilemanager.app/files/x.pdf", privateRoots))
    }

    @Test
    fun blankPathIsTreatedAsPrivate() {
        assertTrue(IncomingFiles.isPrivatePath("", privateRoots))
    }

    @Test
    fun onlySharedStoragePrefixIsAccepted() {
        assertTrue(IncomingFiles.isSharedStoragePath("/storage/emulated/0/Download/a.zip"))
        assertTrue(IncomingFiles.isSharedStoragePath("/storage/0000-1111/DCIM/a.jpg"))
        assertFalse(IncomingFiles.isSharedStoragePath("/data/data/org.openfilemanager.app/cache/x"))
        assertFalse(IncomingFiles.isSharedStoragePath("/sdcard/a.txt")) // رابط رمزي يُطبَّع قبل الفحص
        assertFalse(IncomingFiles.isSharedStoragePath(""))
    }
}
