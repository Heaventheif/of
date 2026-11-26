package org.openfilemanager.app.editor

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openfilemanager.core.AtomicIO
import org.openfilemanager.core.DraftStore

class TextEditorStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** آلة حالات على مجلد مؤقت: كل النداءات متزامنة (Unconfined) وبلا مهلة فعلية. */
    private fun state(
        tmp: TemporaryFolder,
        readOnly: Boolean = false,
        draftsDir: File = File(tmp.root, "drafts"),
        fontsDir: File = File(tmp.root, "fonts"),
        atomicWrite: (Path, ByteArray) -> Unit = { t, b -> AtomicIO.atomicWrite(t) { it.write(b) } },
        readFileBytes: (String) -> ByteArray = { File(it).readBytes() },
        delayFn: suspend (Long) -> Unit = { },
        nowNs: () -> Long = System::nanoTime,
    ): TextEditorState = TextEditorState(
        readOnly = readOnly,
        drafts = DraftStore(draftsDir.toPath()),
        fontsDir = fontsDir,
        io = Dispatchers.Unconfined,
        nowNs = nowNs,
        readFileBytes = readFileBytes,
        atomicWrite = atomicWrite,
        delayFn = delayFn,
    )

    /** كاتب ذري مزيف يوثّق الترتيب: مؤقت ثم (فشل أو) إعادة تسمية، وينظف المؤقت عند الفشل. */
    private class RecordingWriter(private val failAfterTemp: Boolean) {
        val events = mutableListOf<String>()

        val impl: (Path, ByteArray) -> Unit = { target, bytes ->
            val t = target.resolveSibling(".fm-tmp-test.tmp")
            events += "temp"
            Files.write(t, bytes)
            if (failAfterTemp) {
                events += "fail"
                runCatching { Files.deleteIfExists(t) }
                throw IOException("disk full")
            }
            events += "rename"
            Files.move(t, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---- التحميل والنص والاتساخ ----

    @Test
    fun loadSetsTextAndDirtyBaseline() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val st = state(tmp)
        assertTrue(st.loading.value) // يبدأ محمَّلاً كما كانت loading=true سلفاً
        val r = runBlocking { st.load(f.absolutePath) }
        assertNull(r.error)
        assertFalse(r.restoredDraft)
        assertEquals("hello", r.textToOpen)
        assertEquals("hello", st.text.value)
        assertFalse(st.dirty.value)
        assertFalse(st.loading.value)
        assertNull(st.failed.value)
    }

    @Test
    fun loadRestoresNewerDraftAndFlagsIt() {
        val f = tmp.newFile("doc.txt"); f.writeText("abc")
        val store = DraftStore(File(tmp.root, "drafts").toPath())
        store.save(f.absolutePath, "draft text") // مسودة من جلسة انقطعت
        val st = state(tmp)
        val r = runBlocking { st.load(f.absolutePath) }
        assertTrue(r.restoredDraft)
        assertEquals("draft text", r.textToOpen)
        assertEquals("draft text", st.text.value)
        assertTrue(st.dirty.value) // «غير محفوظ» حتى يضغط المستخدم حفظ
    }

    @Test
    fun loadIgnoresDraftIdenticalToOriginal() {
        val f = tmp.newFile("doc.txt"); f.writeText("abc")
        val store = DraftStore(File(tmp.root, "drafts").toPath())
        store.save(f.absolutePath, "abc")
        val st = state(tmp)
        val r = runBlocking { st.load(f.absolutePath) }
        assertFalse(r.restoredDraft)
        assertEquals("abc", r.textToOpen)
        assertFalse(st.dirty.value)
    }

    @Test
    fun loadRejectsOversizeAndMissingFilesAsTooLarge() {
        val st = state(tmp)
        val big = tmp.newFile("big.bin")
        Files.write(big.toPath(), ByteArray(TextEditorState.MAX_BYTES + 1))
        var r = runBlocking { st.load(big.absolutePath) }
        assertEquals(ReadFailure.TooLarge, r.error)
        assertEquals(ReadFailure.TooLarge, st.failed.value)
        assertFalse(st.loading.value)
        assertEquals("", st.text.value)
        // الملف غير الموجود كان يرفض ui_err_too_large سلفاً (!file.isFile) — مثبَّت كما هو
        r = runBlocking { st.load(File(tmp.root, "nope.bin").absolutePath) }
        assertEquals(ReadFailure.TooLarge, r.error)
    }

    @Test
    fun readOnlySuppressesDirtyDraftRestoreAndAutosaveGate() {
        val f = tmp.newFile("doc.txt"); f.writeText("abc")
        val store = DraftStore(File(tmp.root, "drafts").toPath())
        store.save(f.absolutePath, "zzz")
        val st = state(tmp, readOnly = true)
        val r = runBlocking { st.load(f.absolutePath) }
        assertFalse(r.restoredDraft) // للعرض فقط: لا مسودة
        assertEquals("abc", st.text.value)
        st.onEditText("different") // الحقل للعرض فقط سلفاً لا يعدّل؛ ولو غيّر فالاتساخ false
        assertFalse(st.dirty.value)
        assertFalse(st.shouldAutosaveDraft())
    }

    @Test
    fun dirtyTransitionsAcrossEditSaveAndSecondEdit() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("hello!")
        assertTrue(st.dirty.value)
        assertEquals(SaveOutcome.Saved, runBlocking { st.save(f.absolutePath) })
        assertFalse(st.dirty.value) // المرجع تحدّث عند الحفظ
        assertEquals("hello!", f.readText()) // الحفظ نشر النص
        st.onEditText("hello!") // النص نفسه: بلا تغيّر
        assertFalse(st.dirty.value)
        st.onEditText("bye")
        assertTrue(st.dirty.value)
    }

    // ---- الحفظ الذري: الترتيب والرفض والفشل ----

    @Test
    fun saveReplacesFileViaTempThenRename() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val w = RecordingWriter(failAfterTemp = false)
        val st = state(tmp, atomicWrite = w.impl)
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("hello world")
        assertEquals(SaveOutcome.Saved, runBlocking { st.save(f.absolutePath) })
        assertEquals(listOf("temp", "rename"), w.events) // مؤقت ثم إعادة تسمية، بلا استثناء
        assertEquals("hello world", f.readText())
        assertTrue(f.parentFile.listFiles { it -> it.name.startsWith(".fm-tmp-") }.isNullOrEmpty()) // لا بقايا مؤقتة
        assertFalse(st.dirty.value)
    }

    @Test
    fun saveFailingWriteLeavesOriginalIntactAndDraftKept() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val store = DraftStore(File(tmp.root, "drafts").toPath())
        val w = RecordingWriter(failAfterTemp = true)
        val st = state(tmp, atomicWrite = w.impl)
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("partial data that must never land")
        runBlocking { st.autosaveDraft(f.absolutePath) } // مسودة موجودة قبل الحفظ الفاشل
        assertNotNull(store.load(f.absolutePath))
        assertEquals(SaveOutcome.Error, runBlocking { st.save(f.absolutePath) })
        assertEquals(listOf("temp", "fail"), w.events) // فشل قبل إعادة التسمية: لا نشر
        assertEquals("hello", f.readText()) // الأصل سليم بلا محتوى جزئي
        assertNotNull(store.load(f.absolutePath)) // المسودة لم تُحذف (الحذف بعد النشر الناجح فقط)
        assertTrue(st.dirty.value)
        assertTrue(f.parentFile.listFiles { it -> it.name.startsWith(".fm-tmp-") }.isNullOrEmpty()) // المؤقت نُظّف
    }

    @Test
    fun saveRefusesWhenFileChangedUnderneath() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val store = DraftStore(File(tmp.root, "drafts").toPath())
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("hello!")
        runBlocking { st.autosaveDraft(f.absolutePath) }
        Files.write(f.toPath(), "tampered".toByteArray()) // تغيّر الملف تحتنا
        assertEquals(SaveOutcome.Changed, runBlocking { st.save(f.absolutePath) })
        assertEquals("tampered", f.readText()) // رفض الحفظ: محتوى الغير يظل كما هو
        assertNotNull(store.load(f.absolutePath)) // والمسودة لم تُحذف
        assertTrue(st.dirty.value)
    }

    @Test
    fun saveRejectsOversizeResultWithoutTouchingFile() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("a".repeat(TextEditorState.MAX_BYTES + 1))
        assertEquals(SaveOutcome.TooLarge, runBlocking { st.save(f.absolutePath) })
        assertEquals("hello", f.readText())
        assertTrue(st.dirty.value)
    }

    @Test
    fun defaultAtomicWriterReplacesFileKeepsBomAndLeavesNoTemp() {
        val f = tmp.newFile("bom.txt")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        f.writeBytes(bom + "héllo".toByteArray(Charsets.UTF_8))
        val st = state(tmp) // الكاتب الذري الافتراضي: AtomicIO.atomicWrite
        val r = runBlocking { st.load(f.absolutePath) }
        assertEquals("héllo", r.textToOpen)
        st.onEditText("héllo!")
        assertEquals(SaveOutcome.Saved, runBlocking { st.save(f.absolutePath) })
        assertArrayEquals(bom + "héllo!".toByteArray(Charsets.UTF_8), f.readBytes()) // الترميز وBOM الأصليان حُفظا
        assertFalse(st.dirty.value)
        assertTrue(f.parentFile.listFiles { it -> it.name.startsWith(".fm-tmp-") }.isNullOrEmpty())
    }

    // ---- المسودة: البوابة والتوقيت والحذف ----

    @Test
    fun shouldAutosaveDraftGateMatchesOldCondition() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val st = state(tmp)
        assertFalse(st.shouldAutosaveDraft()) // loading=true
        runBlocking { st.load(f.absolutePath) }
        assertFalse(st.shouldAutosaveDraft()) // نظيف
        st.onEditText("hello!")
        assertTrue(st.shouldAutosaveDraft()) // متسخ ومحمَّل وليس للعرض
        st.readOnly = true
        assertFalse(st.shouldAutosaveDraft()) // readOnly يوقفه فوراً
        assertFalse(st.dirty.value) // ويعيد حساب الاتساخ فوراً
        st.readOnly = false
        assertTrue(st.dirty.value)
    }

    @Test
    fun autosaveDraftRequestsDebounceThenWrites() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        var requested = -1L
        val st = state(tmp, delayFn = { requested = it })
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("hello!")
        runBlocking { st.autosaveDraft(f.absolutePath) }
        assertEquals(TextEditorState.DRAFT_AUTOSAVE_DELAY_MS, requested) // مهلة السكون 1500ms كما كانت
        assertEquals("hello!", DraftStore(File(tmp.root, "drafts").toPath()).load(f.absolutePath))
    }

    @Test
    fun cancelledAutosaveWritesNothing() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val gate = CompletableDeferred<Unit>()
        var entered = false
        val st = state(tmp, delayFn = { entered = true; gate.await() })
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("hello!")
        runBlocking {
            val job = launch { st.autosaveDraft(f.absolutePath) }
            while (!entered) yield() // المهمة علّقت داخل المهلة
            job.cancelAndJoin() // كما كانت LaunchedEffect تُلغى عند تغيّر المفتاح
        }
        assertNull(DraftStore(File(tmp.root, "drafts").toPath()).load(f.absolutePath))
    }

    @Test
    fun clearDraftRemovesExistingDraft() {
        val f = tmp.newFile("doc.txt"); f.writeText("hello")
        val store = DraftStore(File(tmp.root, "drafts").toPath())
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onEditText("hello!")
        runBlocking { st.autosaveDraft(f.absolutePath) }
        assertNotNull(store.load(f.absolutePath))
        runBlocking { st.clearDraft(f.absolutePath) } // كما في زر التجاهل
        assertNull(store.load(f.absolutePath))
    }

    @Test
    fun pruneDraftsRemovesOnlyStaleFiles() {
        val store = DraftStore(File(tmp.root, "drafts").toPath())
        store.save("/a.txt", "one")
        store.save("/b.txt", "two")
        val st = state(tmp)
        val a = File(tmp.root, "drafts/${store.keyOf("/a.txt")}.draft")
        assertTrue(
            a.setLastModified(System.currentTimeMillis() - TextEditorState.DRAFT_MAX_AGE_MILLIS - 60_000)
        )
        st.pruneDrafts() // تنظيف عند الفتح: يتيمة > 30 يوماً فقط
        assertFalse(Files.exists(a.toPath()))
        assertNotNull(store.load("/b.txt"))
    }

    // ---- البحث: التطابقات والتنقل والحدود ----

    @Test
    fun searchMatchesCaseInsensitiveAndCapped() {
        val f = tmp.newFile("doc.txt"); f.writeText("ababAB")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onQueryChanged("ab")
        assertEquals(listOf(0, 2, 4), st.matches.value) // بلا حساسية لحالة الأحرف
        val big = tmp.newFile("cap.txt"); big.writeText("ab".repeat(5001)) // 5001 موضعاً
        runBlocking { st.load(big.absolutePath) }
        st.onQueryChanged("ab")
        assertEquals(TextEditorState.MAX_MATCHES, st.matches.value.size) // السقف 5000 كما كان
    }

    @Test
    fun searchEmptyQueryAndNoMatchesEdges() {
        val f = tmp.newFile("doc.txt"); f.writeText("ababAB")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onQueryChanged("") // قيمة فارغة من البداية: بلا فعل
        assertTrue(st.matches.value.isEmpty())
        assertEquals(0, st.navTick.value)
        st.onQueryChanged("zz") // لا تطابقات: تِك واحد لأن الاستعلام غير فارغ (كما كانت LaunchedEffect(query))
        assertTrue(st.matches.value.isEmpty())
        assertEquals(0, st.cur.value)
        assertEquals(1, st.navTick.value)
        st.step(1) // بلا تطابقات: بلا فعل ولا تِك إضافي
        assertEquals(1, st.navTick.value)
        assertEquals(0, st.cur.value)
    }

    @Test
    fun searchStepWrapsAroundBothDirections() {
        val f = tmp.newFile("doc.txt"); f.writeText("ababAB")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onQueryChanged("ab")
        assertEquals(0, st.cur.value)
        st.step(1)
        assertEquals(1, st.cur.value)
        st.step(1)
        assertEquals(2, st.cur.value)
        st.step(1)
        assertEquals(0, st.cur.value) // لفّ من الأخير إلى الأول
        st.step(-1)
        assertEquals(2, st.cur.value) // لفّ من الأول إلى الأخير
        assertEquals(5, st.navTick.value) // تِك الاستعلام الأول + تِك لكل خطوة من الخطوات الأربع
        assertTrue(st.searchNav.value)
    }

    @Test
    fun queryChangeResetsIndexTicksAndSearchNav() {
        val f = tmp.newFile("doc.txt"); f.writeText("ababAB")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onQueryChanged("ab")
        assertEquals(1, st.navTick.value)
        assertTrue(st.searchNav.value)
        assertEquals(0, st.cur.value)
        st.step(1)
        assertEquals(1, st.cur.value)
        st.onQueryChanged("ab") // القيمة نفسها: بلا فعل (كما كانت مفاتيح الـLaunchedEffect)
        assertEquals(2, st.navTick.value)
        assertEquals(1, st.cur.value)
        st.onQueryChanged("") // الإفراغ: العودة لمؤشر الكتابة بلا تِك
        assertFalse(st.searchNav.value)
        assertEquals(0, st.cur.value)
        assertEquals(2, st.navTick.value)
        assertTrue(st.matches.value.isEmpty())
    }

    @Test
    fun curCoercesWhenMatchesShrinkOnEdit() {
        val f = tmp.newFile("doc.txt"); f.writeText("ababAB")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onQueryChanged("ab")
        st.step(1)
        st.step(1)
        assertEquals(2, st.cur.value)
        st.onEditText("ab") // التطابقات تقلّصت: الفهرس المعروض يُقصّ فلا يبقى «12/10»
        assertEquals(listOf(0), st.matches.value)
        assertEquals(0, st.cur.value)
    }

    @Test
    fun toggleSearchClearsQueryOnClose() {
        val f = tmp.newFile("doc.txt"); f.writeText("ababAB")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        assertFalse(st.searching.value)
        st.toggleSearch()
        assertTrue(st.searching.value)
        st.onQueryChanged("ab")
        assertEquals(3, st.matches.value.size)
        st.toggleSearch() // الإغلاق يفرغ الاستعلام كما كانت L323
        assertFalse(st.searching.value)
        assertEquals("", st.query.value)
        assertTrue(st.matches.value.isEmpty())
        assertFalse(st.searchNav.value)
    }

    @Test
    fun clearSearchNavOnEditRestoresCaretFollow() {
        val f = tmp.newFile("doc.txt"); f.writeText("ababAB")
        val st = state(tmp)
        runBlocking { st.load(f.absolutePath) }
        st.onQueryChanged("ab")
        assertTrue(st.searchNav.value)
        st.clearSearchNav() // كما يفعل onValueChange عند أي تحرير فعلي أو تحريك مؤشر
        assertFalse(st.searchNav.value)
    }

    @Test
    fun matchScrollTargetAndFarJumpMath() {
        val st = state(tmp)
        // الثلث محصور بين 48 و240 بكسل، والهدف لا ينزل تحت الصفر
        assertEquals(900, st.matchScrollTarget(top = 1000f, viewportH = 300))
        assertEquals(0, st.matchScrollTarget(top = 10f, viewportH = 300))
        assertEquals(952, st.matchScrollTarget(top = 1000f, viewportH = 60)) // 60/3=20 → الحد الأدنى 48
        assertEquals(760, st.matchScrollTarget(top = 1000f, viewportH = 900)) // 900/3=300 → الحد الأعلى 240
        assertTrue(st.isFarJump(target = 3001, currentScroll = 1000)) // > 2000
        assertFalse(st.isFarJump(target = 3000, currentScroll = 1000)) // 2000 بالضبط: تحريك سلس
        assertFalse(st.isFarJump(target = 1200, currentScroll = 1000))
    }

    // ---- استيراد الخطوط: القرار والحالة والأسماء ----

    @Test
    fun importFontCopiesAndSanitizesName() {
        val fonts = tmp.newFolder("fonts")
        val st = state(tmp, fontsDir = fonts)
        val name = runBlocking {
            st.importFont("My Font.TTF", { ByteArrayInputStream("FONTDATA".toByteArray()) }, { true })
        }
        assertEquals("My Font.TTF", name)
        assertEquals("FONTDATA", File(fonts, "My Font.TTF").readText())
        assertTrue(fonts.listFiles()!!.all { !it.name.startsWith(".import-") }) // لا بقايا مؤقتة
        st.onFontImported()
        assertEquals(listOf("My Font.TTF"), st.importedFonts.value)
        assertEquals(FontNote.Imported, st.fontNote.value)
    }

    @Test
    fun importFontRejectsBadExtensionNullDisplayAndNullStream() {
        val fonts = tmp.newFolder("fonts")
        val st = state(tmp, fontsDir = fonts)
        val ok: (File) -> Boolean = { true }
        assertNull(runBlocking { st.importFont("foo.txt", { ByteArrayInputStream(ByteArray(1)) }, ok) })
        assertNull(runBlocking { st.importFont("noext", { ByteArrayInputStream(ByteArray(1)) }, ok) })
        assertNull(runBlocking { st.importFont(null, { ByteArrayInputStream(ByteArray(1)) }, ok) }) // اسم العميل غائب
        assertNull(runBlocking { st.importFont("F.ttf", null, ok) }) // لا دفق (كان openInputStream null)
        assertTrue(fonts.listFiles()!!.isEmpty()) // لا ملفات ولا مجلد فرعي حُقن
    }

    @Test
    fun importFontRejectsFailedTypefaceCheckAndCleansTemp() {
        val fonts = tmp.newFolder("fonts")
        val st = state(tmp, fontsDir = fonts)
        val name = runBlocking {
            st.importFont("Broken.ttf", { ByteArrayInputStream("GARBAGE".toByteArray()) }, { false })
        }
        assertNull(name)
        assertTrue(fonts.listFiles()!!.isEmpty()) // المؤقت نُظّف ولم يُنشر شيء
        st.onFontImportFailed()
        assertEquals(FontNote.Error, st.fontNote.value)
    }

    @Test
    fun importFontRejectsOversizeStreamAndCleansTemp() {
        val fonts = tmp.newFolder("fonts")
        val st = state(tmp, fontsDir = fonts)
        val huge = object : InputStream() {
            var remaining = TextEditorState.MAX_FONT_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 'a'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                java.util.Arrays.fill(b, off, off + n, 'a'.code.toByte())
                remaining -= n
                return n
            }
        }
        assertNull(runBlocking { st.importFont("Huge.ttf", { huge }, { true }) })
        assertTrue(fonts.listFiles()!!.isEmpty())
    }

    @Test
    fun importFontReplacesExistingTarget() {
        val fonts = tmp.newFolder("fonts")
        val st = state(tmp, fontsDir = fonts)
        val first = runBlocking { st.importFont("F.ttf", { ByteArrayInputStream("AAA".toByteArray()) }, { true }) }
        assertEquals("F.ttf", first)
        val second = runBlocking { st.importFont("F.ttf", { ByteArrayInputStream("BBB".toByteArray()) }, { true }) }
        assertEquals("F.ttf", second)
        assertEquals("BBB", File(fonts, "F.ttf").readText()) // الاستبدال كما كان (حذف الهدف ثم النقل)
    }

    @Test
    fun deleteImportedFontRemovesFileAndRefreshes() {
        val fonts = tmp.newFolder("fonts")
        val st = state(tmp, fontsDir = fonts)
        runBlocking {
            st.importFont("A.ttf", { ByteArrayInputStream("A".toByteArray()) }, { true })
            st.importFont("B.ttf", { ByteArrayInputStream("B".toByteArray()) }, { true })
        }
        st.refreshImportedFonts()
        assertEquals(listOf("A.ttf", "B.ttf"), st.importedFonts.value)
        st.deleteImportedFont("A.ttf") // كما في أيقونة الحذف
        assertFalse(File(fonts, "A.ttf").exists())
        assertEquals(listOf("B.ttf"), st.importedFonts.value)
    }

    @Test
    fun fontNoteTransitionsOnPickImportAndFailure() {
        val st = state(tmp)
        st.onFontImportFailed()
        assertEquals(FontNote.Error, st.fontNote.value)
        st.onFontPickRequested() // ضغط زر الاستيراد يزول الملاحظة قبل فتح المنتقي
        assertNull(st.fontNote.value)
        st.onFontImported()
        assertEquals(FontNote.Imported, st.fontNote.value)
    }

    @Test
    fun sanitizeDerivesSafeName() {
        val st = state(tmp)
        assertEquals("a_b_c.ttf", st.sanitize("a/b\u0000c.ttf")) // المحارف خارج اللغة/الرقم تُستبدل
        assertEquals("ttf", st.sanitize("....ttf")) // النقاط الابتدائية تُزال
        assertEquals(80, st.sanitize("a".repeat(100) + ".ttf")!!.length) // القصّ إلى 80
        assertNull(st.sanitize("...")) // فارغ بعد الإزالة: رفض
        assertNull(st.sanitize(""))
    }

    @Test
    fun refreshImportedFontsListsNonHiddenSorted() {
        val fonts = tmp.newFolder("fonts")
        File(fonts, "b.ttf").createNewFile()
        File(fonts, "a.ttf").createNewFile()
        File(fonts, ".hidden").createNewFile()
        File(fonts, "sub").mkdir()
        val st = state(tmp, fontsDir = fonts)
        st.refreshImportedFonts()
        assertEquals(listOf("a.ttf", "b.ttf"), st.importedFonts.value) // ملفات عادية فقط ومرتبة
    }
}
