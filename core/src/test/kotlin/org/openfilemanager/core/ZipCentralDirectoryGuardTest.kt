package org.openfilemanager.core

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ZipCentralDirectoryGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String): Path = tmp.newFolder(name).toPath()

    // ---------- أدوات بناء البايتات ----------

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun le64(v: Long) = le32(v and 0xFFFFFFFFL) + le32((v ushr 32) and 0xFFFFFFFFL)

    /** قراءة le32 من بايتات (للتحقق من حقول EOCD قبل تزييفها). */
    private fun leOf(b: ByteArray, pos: Int): Long =
        (b[pos].toLong() and 0xFF) or ((b[pos + 1].toLong() and 0xFF) shl 8) or
            ((b[pos + 2].toLong() and 0xFF) shl 16) or ((b[pos + 3].toLong() and 0xFF) shl 24)

    private fun eocd(
        declared: Int,
        cdSize: Long,
        cdOffset: Long,
        comment: ByteArray = ByteArray(0),
        zip64Masked: Boolean = false
    ): ByteArray {
        val countField = if (zip64Masked) 0xFFFF else declared
        val sizeField = if (zip64Masked) 0xFFFFFFFFL else cdSize
        val offsetField = if (zip64Masked) 0xFFFFFFFFL else cdOffset
        return byteArrayOf(0x50, 0x4b, 0x05, 0x06) +
            le16(0) + le16(0) +            // قرصان
            le16(if (zip64Masked) 0xFFFF else declared) +
            le16(countField) +
            le32(sizeField) + le32(offsetField) +
            le16(comment.size) + comment
    }

    private fun zip64Record(declared: Long, cdSize: Long, cdOffset: Long): ByteArray =
        byteArrayOf(0x50, 0x4b, 0x06, 0x06) +
            le64(ZIP64_RECORD_SIZE - 12L) +
            le16(45) + le16(45) + le32(0) + le32(0) +
            le64(declared) + le64(declared) +
            le64(cdSize) + le64(cdOffset)

    private fun zip64Locator(recordOffset: Long): ByteArray =
        byteArrayOf(0x50, 0x4b, 0x06, 0x07) + le32(0) + le64(recordOffset) + le32(1)

    private val ZIP64_RECORD_SIZE = 56

    /** يكتب بصمة ZIP في المقدمة + جسماً وهمياً + سجل ZIP64 اختياري + EOCD. */
    private fun crafted(
        folder: String,
        payloadSize: Int,
        declared: Int,
        cdSize: Long,
        cdOffset: Long,
        zip64: Triple<Long, Long, Long>? = null,
        comment: ByteArray = ByteArray(0)
    ): Path {
        val p = dir(folder).resolve("crafted.zip")
        // بصمة PK\x03\x04 في المقدمة (تخليص الكشف) ثم جسد وهمي لا يصلح دليلاً
        // مركزياً: لو وصل ZipFile لرمى خطأه الخاص، فحصول ArchiveLimitException
        // في الاختبارات يثبت أن الرفض سبق الفتح.
        val magic = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val payload = magic + ByteArray(maxOf(0, payloadSize - magic.size)) { 'x'.code.toByte() }
        val bytes = if (zip64 != null) {
            val rec = zip64Record(zip64.first, zip64.second, zip64.third)
            val loc = zip64Locator(payloadSize.toLong())
            payload + rec + loc + eocd(declared, cdSize, cdOffset, comment, zip64Masked = true)
        } else {
            payload + eocd(declared, cdSize, cdOffset, comment)
        }
        Files.write(p, bytes)
        return p
    }

    private fun realZip(folder: String, count: Int, prefix: String = "e"): Path {
        val p = dir(folder).resolve("real.zip")
        ZipArchiveOutputStream(Files.newOutputStream(p)).use { z ->
            for (i in 1..count) {
                z.putArchiveEntry(ZipArchiveEntry("$prefix$i.txt"))
                z.write("content-$i".toByteArray())
                z.closeArchiveEntry()
            }
        }
        return p
    }

    // ---------- أحكام الفحص نفسه ----------

    @Test
    fun guardVerdicts() {
        // سليم: Pass بعدد معلن صحيح.
        val ok = ZipCentralDirectoryCheck.check(realZip("verdict-ok", 30), 5_000)
        assertTrue(ok is ZipCentralDirectoryCheck.Verdict.Pass)
        assertEquals(30, (ok as ZipCentralDirectoryCheck.Verdict.Pass).info.declaredEntries)
        assertFalse(ok.info.zip64)

        // فوق السقف معلناً: OverLimit قبل أي فتح.
        val over = crafted("verdict-over", 512, declared = 60_000, cdSize = 512, cdOffset = 0)
        assertEquals(
            ZipCentralDirectoryCheck.Verdict.OverLimit(60_000L),
            ZipCentralDirectoryCheck.check(over, 5_000)
        )

        // حجم الدليل المركزي أكبر من الملف: Inconsistent (EOCD مقروء لا يُتجاهل).
        val inc = crafted("verdict-inc", 512, declared = 10, cdSize = 3_000_000_000L, cdOffset = 0)
        assertTrue(ZipCentralDirectoryCheck.check(inc, 5_000) is ZipCentralDirectoryCheck.Verdict.Inconsistent)

        // إزاحة خارج الملف: Inconsistent.
        val incOff = crafted("verdict-incoff", 512, declared = 10, cdSize = 16, cdOffset = 4_000_000_000L)
        assertTrue(ZipCentralDirectoryCheck.check(incOff, 5_000) is ZipCentralDirectoryCheck.Verdict.Inconsistent)

        // ZIP64 معلن ضخم: OverLimit بقيمة ZIP64.
        val z64 = crafted(
            "verdict-z64", 512, declared = 0,
            cdSize = 512, cdOffset = 0,
            zip64 = Triple(1_000_000L, 512L, 0L)
        )
        val v = ZipCentralDirectoryCheck.check(z64, 5_000)
        assertEquals(ZipCentralDirectoryCheck.Verdict.OverLimit(1_000_000L), v)

        // بلا EOCD: Unreadable — المسار القديم يحسم.
        val garbage = dir("verdict-garbage").resolve("g.zip")
        Files.write(garbage, ByteArray(4_096) { (it % 251).toByte() })
        assertTrue(ZipCentralDirectoryCheck.check(garbage, 5_000) is ZipCentralDirectoryCheck.Verdict.Unreadable)
    }

    @Test
    fun zip64RealArchivePassesWithZip64Flag() {
        val p = dir("z64-real").resolve("r.zip")
        ZipArchiveOutputStream(Files.newOutputStream(p)).use { z ->
            z.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.Always)
            for (i in 1..10) {
                z.putArchiveEntry(ZipArchiveEntry("z$i.txt"))
                z.write("x".toByteArray())
                z.closeArchiveEntry()
            }
        }
        val verdict = ZipCentralDirectoryCheck.check(p, 5_000)
        assertTrue(verdict is ZipCentralDirectoryCheck.Verdict.Pass)
        assertTrue((verdict as ZipCentralDirectoryCheck.Verdict.Pass).info.zip64)
        assertEquals(10, verdict.info.declaredEntries)
    }

    // ---------- مسار المعاينة: الرفض قبل فتح ZipFile ----------

    @Test
    fun overCapDeclaredArchiveIsRejectedByPreviewWithoutOpeningZipFile() {
        // الجسد «دليل مركزي» فاسد عمداً: لو فُتح ZipFile لرمى ZipException
        // — الحصول على ArchiveLimitException يثبت أن الرفض سبق الفتح.
        val p = crafted("preview-over", 512, declared = 60_000, cdSize = 512, cdOffset = 0)
        val e = try {
            ArchiveInspector.list(p, maxEntries = 5_000); null
        } catch (e: Exception) { e }
        assertNotNull("المصنوع فوق السقف يجب أن يُرفض", e)
        assertTrue("نوع الخطأ نفسه لمسار الحدود الحالي", e is ArchiveLimitException)
    }

    @Test
    fun zip64OverCapArchiveIsRejectedByPreview() {
        val p = crafted(
            "preview-z64", 512, declared = 0, cdSize = 512, cdOffset = 0,
            zip64 = Triple(1_000_000L, 512L, 0L)
        )
        val e = try { ArchiveInspector.list(p, maxEntries = 5_000); null } catch (e: Exception) { e }
        assertTrue(e is ArchiveLimitException)
    }

    @Test
    fun inconsistentCentralDirectoryIsRejectedByPreview() {
        val p = crafted("preview-inc", 512, declared = 10, cdSize = 3_000_000_000L, cdOffset = 0)
        val e = try { ArchiveInspector.list(p, maxEntries = 5_000); null } catch (e: Exception) { e }
        assertTrue("EOCD مقروء بحقول مستحيلة لا يُتجاهل بصمت", e is ArchiveLimitException)
    }

    @Test
    fun overCapDeclaredArchiveRefusesEditingWithSameReason() {
        val p = crafted("load-over", 512, declared = 60_000, cdSize = 512, cdOffset = 0)
        val load = ZipModel.load(p, maxEntries = 5_000)
        assertNull("لا نموذج تحرير", load.model)
        assertEquals("نفس سبب تجاوز السقف الحالي حرفياً", "too-many-entries", load.reason)
    }

    @Test
    fun inconsistentCentralDirectoryRefusesEditingWithSameReason() {
        val p = crafted("load-inc", 512, declared = 10, cdSize = 3_000_000_000L, cdOffset = 0)
        val load = ZipModel.load(p, maxEntries = 5_000)
        assertNull(load.model)
        assertEquals("too-many-entries", load.reason)
    }

    // ---------- لا انحدار للأرشيفات السليمة ----------

    @Test
    fun underCapPreviewIsUnchangedAfterGuard() {
        val p = realZip("preview-ok", 300)
        val listing = ArchiveInspector.list(p, maxEntries = 5_000)
        assertEquals(300, listing.scanned)
        assertFalse(listing.truncated)
        assertEquals("e1.txt", listing.entries.first().name)
        assertEquals(300, ZipModel.load(p).model!!.entries().size)
    }

    @Test
    fun prefixedArchiveStillLoadsForEditing() {
        // بادئة تشبه SFX: تحرير الأرشيف لا يمر بكشف الصيغة، وZipFile يتعامل
        // مع البادئة، والفحص الجديد لا يرفضها لأن الحجم/الإزاحة داخل طول الملف.
        val folder = dir("sfx")
        val plain = folder.resolve("plain.zip")
        ZipArchiveOutputStream(Files.newOutputStream(plain)).use { z ->
            for (i in 1..40) {
                z.putArchiveEntry(ZipArchiveEntry("p$i.txt"))
                z.write("content-$i".toByteArray())
                z.closeArchiveEntry()
            }
        }
        val prefixed = folder.resolve("prefixed.zip")
        Files.write(prefixed, ByteArray(128) { 'S'.code.toByte() } + Files.readAllBytes(plain))
        val verdict = ZipCentralDirectoryCheck.check(prefixed, 5_000)
        assertTrue("البادئة لا تكسر شرط الاتساق", verdict is ZipCentralDirectoryCheck.Verdict.Pass)
        assertTrue("التحرير يفتح كما اليوم", ZipModel.load(prefixed).editable)
    }

    @Test
    fun exactlyAtCapDeclaredCountPasses() {
        val p = realZip("at-cap", 5_000)
        val verdict = ZipCentralDirectoryCheck.check(p, 5_000)
        assertTrue(verdict is ZipCentralDirectoryCheck.Verdict.Pass)
        assertEquals(5_000, (verdict as ZipCentralDirectoryCheck.Verdict.Pass).info.declaredEntries)
    }

    // ---------- سقف بايتات الدليل المركزي نفسه (مدمج من variant B) ----------

    @Test
    fun centralDirectoryByteCapIsEnforced() {
        // عدد معلن تحت السقف، وحجم الدليل المركزي متسق مع طول الملف لكنه يتجاوز
        // السقف البايتي: يُرفض قبل الفتح (فهرسته المكاملة هي خطر الذاكرة هنا).
        // الجسم كبير بما يكفي ليكون EOCD متسقاً (الاتساق أولاً ثم السقف).
        val p = crafted("cd-cap", payloadSize = 200, declared = 3, cdSize = 100L, cdOffset = 0L)
        val verdict = ZipCentralDirectoryCheck.check(p, 100, maxDirectoryBytes = 10)
        assertTrue("الحجم فوق السقف البايتي يُرفض", verdict is ZipCentralDirectoryCheck.Verdict.CentralDirectoryTooLarge)
        assertEquals(100L, (verdict as ZipCentralDirectoryCheck.Verdict.CentralDirectoryTooLarge).size)
        assertEquals(10L, verdict.cap)
    }

    @Test
    fun centralDirectoryUnderByteCapPasses() {
        // نفس البصمة تحت السقف البايتي: Pass كالعادة (لا تأثير على السليم).
        val p = crafted("cd-ok", payloadSize = 64, declared = 3, cdSize = 10L, cdOffset = 0L)
        val verdict = ZipCentralDirectoryCheck.check(p, 100, maxDirectoryBytes = 64)
        assertTrue(verdict is ZipCentralDirectoryCheck.Verdict.Pass)
    }

    @Test
    fun realSmallZipPassesDefaultByteCap() {
        // أرشيف حقيقي عادي: دليله المركزي أصغر بكثير من السقف الافتراضي 64 MiB.
        val p = realZip("cd-default", 20)
        assertTrue(
            ZipCentralDirectoryCheck.check(p, 100) is ZipCentralDirectoryCheck.Verdict.Pass
        )
    }

    // ---------- (E4) العدّ الفعلي لرؤوس الدليل المركزي ----------

    /** العيب E4 نفسه: أرشيف حقيقي بعشرين مدخلاً في دليله المركزي، لكن EOCD يعلن اثنين فقط. */
    @Test
    fun understatedEntryCountIsCaughtByActualHeaderScan_PinsE4() {
        val p = realZip("e4-under", 20)
        val bytes = Files.readAllBytes(p)
        val eocd = bytes.size - 22
        assertEquals("EOCD signature", 0x06054b50L, leOf(bytes, eocd))
        // حقل «عدد مداخل الدليل» عند إزاحة 10 من EOCD: نكذب على الفحص.
        bytes[eocd + 10] = 2.toByte()
        bytes[eocd + 11] = 0.toByte()
        Files.write(p, bytes)

        val verdict = ZipCentralDirectoryCheck.check(p, 10)
        assertTrue(
            "الفحص يجب أن يرفض بالعدد الفعلي (20) لا المعلن (2)",
            verdict is ZipCentralDirectoryCheck.Verdict.OverLimit
        )
        // توقف مبكر: أول قيمة فوق السقف (11) تكفي، بلا قراءة بقية الدليل.
        assertEquals(11L, (verdict as ZipCentralDirectoryCheck.Verdict.OverLimit).declaredEntries)
    }

    /**
     * تسامح المشي: إزاحة دليل لا تشير لتوقيع رأس صالح (بادئة SFX أو أرشيف
     * شاذ) توقف العدّ وتعيد العهدة للعدد المعلن — لا رفض جديد على ما كان
     * يفتح قبل الإصلاح.
     */
    @Test
    fun misalignedCentralDirectoryWalkFallsBackToDeclaredCount() {
        val p = crafted("e4-tolerant", 512, declared = 3, cdSize = 100L, cdOffset = 4L)
        val verdict = ZipCentralDirectoryCheck.check(p, 100)
        assertTrue(
            "الإزاحة داخل جسد لا رأس فيه: Pass كما قبل الإصلاح",
            verdict is ZipCentralDirectoryCheck.Verdict.Pass
        )
    }

    @Test
    fun overByteCapPreviewThrowsBeforeOpeningAndLoadConflatesReason() {
        // المعاينة: ArchiveLimitException قبل فتح ZipFile. السقف هنا الافتراضي
        // نفسه (64 MiB): جسم الملف أكبر من الدليل المعلن (متسق) والدليل يتجاوز
        // السقف البايتي — فالرفض من الحد لا من الاتساق.
        val p = crafted(
            "cd-preview", payloadSize = 64 * 1024 * 1024, declared = 3,
            cdSize = ZipCentralDirectoryCheck.MAX_DIRECTORY_BYTES + 1L, cdOffset = 0L
        )
        try {
            ArchiveInspector.list(p, Charsets.UTF_8, maxEntries = 100)
            org.junit.Assert.fail("must be rejected before ZipFile")
        } catch (e: ArchiveLimitException) {
            assertEquals(
                "central directory exceeds ${ZipCentralDirectoryCheck.MAX_DIRECTORY_BYTES} bytes",
                e.message
            )
        }
        val load = ZipModel.load(p)
        assertFalse(load.editable)
        assertEquals("too-many-entries", load.reason)
    }
}
