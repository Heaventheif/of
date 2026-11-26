package org.openfilemanager.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openfilemanager.core.ArchiveLimitException
import org.openfilemanager.core.ExtractLimits
import java.io.File

class SpaceBoundLimitsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val unreadableMessage = "archive space unreadable"

    private fun limits(dir: File, relaxed: Boolean, reader: (File) -> Long?): ExtractLimits =
        SpaceBoundLimits.forDestination(dir, relaxed, unreadableMessage, usableSpaceOf = reader)

    // ---- المساحة صفر: رفض برسالة المساحة غير المقروءة (لا Long.MAX_VALUE) ----
    @Test
    fun zeroUsableSpaceRefusesExtraction() {
        val dir = tmp.newFolder("zero")
        try {
            limits(dir, relaxed = false, reader = { 0L })
            fail("expected ArchiveLimitException")
        } catch (e: ArchiveLimitException) {
            assertEquals(unreadableMessage, e.message)
        }
    }

    // ---- المساحة غير متاحة (تعذر القراءة): رفض نفسه ----
    @Test
    fun unreadableSpaceRefusesExtraction() {
        val dir = tmp.newFolder("null")
        try {
            limits(dir, relaxed = false, reader = { null })
            fail("expected ArchiveLimitException")
        } catch (e: ArchiveLimitException) {
            assertEquals(unreadableMessage, e.message)
        }
    }

    // ---- مساحة صغيرة: الحد الكلي = المساحة، وبقية الحقول الافتراضية ----
    @Test
    fun smallUsableSpaceGivesExactTotalLimit() {
        val dir = tmp.newFolder("small")
        val result = limits(dir, relaxed = false, reader = { 1_000L })
        assertEquals(ExtractLimits(maxTotalBytes = 1_000L), result)
    }

    // ---- مساحة كبيرة: الحد الكلي = المساحة نفسها ----
    @Test
    fun largeUsableSpaceGivesExactTotalLimit() {
        val dir = tmp.newFolder("large")
        val big = Long.MAX_VALUE / 2
        val result = limits(dir, relaxed = false, reader = { big })
        assertEquals(ExtractLimits(maxTotalBytes = big), result)
    }

    // ---- المخففة مفعّلة: ExtractLimits.relaxed بالتساوي الكامل ----
    @Test
    fun relaxedModeGivesRelaxedLimits() {
        val dir = tmp.newFolder("relaxed")
        val result = limits(dir, relaxed = true, reader = { 5_000L })
        assertEquals(ExtractLimits.relaxed(5_000L), result)
    }

    // ---- المخففة معطلة: الحدود القياسية نفسها ----
    @Test
    fun nonRelaxedModeGivesStandardLimits() {
        val dir = tmp.newFolder("strict")
        val result = limits(dir, relaxed = false, reader = { 5_000L })
        assertEquals(200L, result.maxRatio)
        assertEquals(200L, result.maxTotalRatio)
        assertEquals(5_000L, result.maxTotalBytes)
    }

    // ---- الوجهة غير موجودة بعد: يُستفسر أقرب أب موجود ----
    @Test
    fun missingDestinationProbesNearestExistingParent() {
        val parent = tmp.newFolder("parent")
        val missing = File(parent, "not-created-yet/deeper")
        val probed = mutableListOf<File>()
        val result = limits(missing, relaxed = false, reader = { f -> probed.add(f); 2_000L })
        assertEquals(2_000L, result.maxTotalBytes)
        assertEquals("أقرب أب موجود هو المفحوص", parent.absolutePath, probed.single().absolutePath)
    }

    // ---- الوجهة موجودة: تُفحص هي نفسها ----
    @Test
    fun existingDestinationIsProbedDirectly() {
        val dir = tmp.newFolder("exists")
        val probed = mutableListOf<File>()
        limits(dir, relaxed = false, reader = { f -> probed.add(f); 3_000L })
        assertEquals(dir.absolutePath, probed.single().absolutePath)
    }

    // ---- لا أب موجود إطلاقاً: probe ينفد فتُرفض (نفس نصّي المصدرين) ----
    @Test
    fun exhaustedProbeChainRefusesExtraction() {
        val missing = File(tmp.root, "a/b/c/d")
        try {
            limits(missing, relaxed = false, reader = { null })
            fail("expected ArchiveLimitException")
        } catch (e: ArchiveLimitException) {
            assertEquals(unreadableMessage, e.message)
        }
    }

    // ---- القارئ الافتراضي (الإنتاج): مساحة حقيقية موجبة من نظام الملفات ----
    @Test
    fun defaultReaderReturnsPositiveSpaceOnRealFs() {
        val dir = tmp.newFolder("real")
        val result = SpaceBoundLimits.forDestination(
            dir, relaxed = false, unreadableMessage = unreadableMessage
        )
        assertTrue("مساحة حقيقية > 0 على نظام الملفات المحلي", result.maxTotalBytes > 0)
    }

    // ---- (N1/D6) مسار المقدمين الموحّد: تطابق نتيجة forDestination حرفياً ----
    @Test
    fun spaceBoundProvidersMatchForDestinationExactly() {
        val dir = tmp.newFolder("unified")
        for (relaxed in listOf(false, true)) {
            val viaProviders = SpaceBoundLimits.spaceBound(
                dir, relaxed = { relaxed }, unreadableMessage = { unreadableMessage }, usableSpaceOf = { 7_777L }
            )
            val direct = SpaceBoundLimits.forDestination(
                dir, relaxed, unreadableMessage, usableSpaceOf = { 7_777L }
            )
            assertEquals("تطابق المسارين عند relaxed=$relaxed", direct, viaProviders)
        }
    }

    // ---- (N1/D6) فشل قراءة المساحة عبر مسار المقدمين: رسالة المقدّم نفسها ----
    @Test
    fun spaceBoundUnreadableUsesTheProviderMessage() {
        val dir = tmp.newFolder("unified-zero")
        try {
            SpaceBoundLimits.spaceBound(
                dir, relaxed = { false }, unreadableMessage = { unreadableMessage }, usableSpaceOf = { 0L }
            )
            fail("expected ArchiveLimitException")
        } catch (e: ArchiveLimitException) {
            assertEquals(unreadableMessage, e.message)
        }
    }
}
