package org.openfilemanager.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Paths

class OpRequestCodecTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun root() = tmp.newFolder("codec")

    // ---------- decode direction: StoredOpRequest -> OpRequest ----------

    @Test fun storedCopyBecomesCopyRequest() {
        val stored = StoredOpRequest(PendingRequestStore.KIND_COPY, listOf("/tmp/a.txt", "/tmp/b.txt"), "/tmp/dst")
        assertEquals(
            OpRequest.Copy(listOf(Paths.get("/tmp/a.txt"), Paths.get("/tmp/b.txt")), Paths.get("/tmp/dst"), "op-copy1"),
            stored.toOpRequest("op-copy1")
        )
    }

    @Test fun storedMoveBecomesMoveRequest() {
        val stored = StoredOpRequest(PendingRequestStore.KIND_MOVE, listOf("/tmp/a.txt"), "/tmp/dst")
        assertEquals(
            OpRequest.Move(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "op-move1"),
            stored.toOpRequest("op-move1")
        )
    }

    @Test fun storedDeleteKeepsNullTargetAndFullPathList() {
        val stored = StoredOpRequest(PendingRequestStore.KIND_DELETE, listOf("/tmp/a.txt", "/tmp/b.txt"), null)
        assertEquals(
            OpRequest.Delete(listOf(Paths.get("/tmp/a.txt"), Paths.get("/tmp/b.txt")), "op-del1"),
            stored.toOpRequest("op-del1")
        )
    }

    @Test fun storedCompressCarriesTargetNameInExtra() {
        val stored = StoredOpRequest(PendingRequestStore.KIND_COMPRESS, listOf("/tmp/a.txt"), "/tmp/dst", extra = "arch.zip")
        assertEquals(
            OpRequest.Compress(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "arch.zip", "op-comp1"),
            stored.toOpRequest("op-comp1")
        )
    }

    @Test fun storedExtractCarriesCharsetSelectionAndDirectFlag() {
        val stored = StoredOpRequest(
            PendingRequestStore.KIND_EXTRACT, listOf("/tmp/a.zip"), "/tmp/dst",
            extra = encodeExtractExtra("windows-1256", direct = true),
            selected = listOf("dir/", "dir/file.txt")
        )
        assertEquals(
            OpRequest.Extract(
                Paths.get("/tmp/a.zip"), Paths.get("/tmp/dst"), "windows-1256",
                listOf("dir/", "dir/file.txt"), "op-ext1", placeDirectly = true
            ),
            stored.toOpRequest("op-ext1")
        )
    }

    @Test fun storedExtractNullSelectedStaysNullAndEmptyStaysEmpty() {
        val withoutSelection = StoredOpRequest(PendingRequestStore.KIND_EXTRACT, listOf("/tmp/a.zip"), "/tmp/dst", extra = "UTF-8")
        val decoded = withoutSelection.toOpRequest("op-ext2")!! as OpRequest.Extract
        assertNull(decoded.selected)
        assertEquals("UTF-8", decoded.charsetName)
        assertFalse(decoded.placeDirectly)

        val emptySelection = StoredOpRequest(PendingRequestStore.KIND_EXTRACT, listOf("/tmp/a.zip"), "/tmp/dst", extra = "UTF-8", selected = emptyList())
        val decodedEmpty = emptySelection.toOpRequest("op-ext3")!! as OpRequest.Extract
        assertEquals(emptyList<String>(), decodedEmpty.selected)
    }

    @Test fun targetedStoredKindsWithoutTargetDecodeToNull() {
        assertNull(StoredOpRequest(PendingRequestStore.KIND_COPY, listOf("/tmp/a.txt"), null).toOpRequest("op-x"))
        assertNull(StoredOpRequest(PendingRequestStore.KIND_MOVE, listOf("/tmp/a.txt"), null).toOpRequest("op-x"))
        assertNull(StoredOpRequest(PendingRequestStore.KIND_COMPRESS, listOf("/tmp/a.txt"), null, extra = "arch.zip").toOpRequest("op-x"))
        assertNull(StoredOpRequest(PendingRequestStore.KIND_EXTRACT, listOf("/tmp/a.zip"), null, extra = "UTF-8").toOpRequest("op-x"))
    }

    @Test fun unknownStoredKindDecodesToNull() {
        assertNull(StoredOpRequest(PendingRequestStore.KIND_VIEWER_IMAGES, listOf("/tmp/a.jpg"), null).toOpRequest("op-u"))
    }

    // ---------- persisted round trip: write -> holdAndRead -> toOpRequest ---------- The write(...) arguments below are copied verbatim from the per-kind…

    @Test fun persistedCopyRoundTripsWithSameOpId() {
        val dir = root()
        val token = PendingRequestStore.write(dir, PendingRequestStore.KIND_COPY, listOf("/tmp/a.txt", "/tmp/b.txt"), "/tmp/dst")
        val stored = PendingRequestStore.holdAndRead(dir, token, "op-rt1")!!
        assertEquals(
            OpRequest.Copy(listOf(Paths.get("/tmp/a.txt"), Paths.get("/tmp/b.txt")), Paths.get("/tmp/dst"), "op-rt1"),
            stored.toOpRequest("op-rt1")
        )
    }

    @Test fun persistedMoveRoundTripsWithSameOpId() {
        val dir = root()
        val token = PendingRequestStore.write(dir, PendingRequestStore.KIND_MOVE, listOf("/tmp/a.txt"), "/tmp/dst")
        val stored = PendingRequestStore.holdAndRead(dir, token, "op-rt2")!!
        assertEquals(
            OpRequest.Move(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "op-rt2"),
            stored.toOpRequest("op-rt2")
        )
    }

    @Test fun persistedDeleteRoundTripsWithoutTarget() {
        val dir = root()
        val token = PendingRequestStore.write(dir, PendingRequestStore.KIND_DELETE, listOf("/tmp/a.txt", "/tmp/b.txt"), null)
        val stored = PendingRequestStore.holdAndRead(dir, token, "op-rt3")!!
        assertNull(stored.target)
        assertEquals(
            OpRequest.Delete(listOf(Paths.get("/tmp/a.txt"), Paths.get("/tmp/b.txt")), "op-rt3"),
            stored.toOpRequest("op-rt3")
        )
    }

    @Test fun persistedCompressRoundTripsTargetNameThroughExtra() {
        val dir = root()
        val token = PendingRequestStore.write(dir, PendingRequestStore.KIND_COMPRESS, listOf("/tmp/a.txt"), "/tmp/dst", extra = "arch.zip")
        val stored = PendingRequestStore.holdAndRead(dir, token, "op-rt4")!!
        assertEquals(
            OpRequest.Compress(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "arch.zip", "op-rt4"),
            stored.toOpRequest("op-rt4")
        )
    }

    @Test fun persistedExtractRoundTripsCharsetSelectedAndDirectFlag() {
        val dir = root()
        val token = PendingRequestStore.write(
            dir, PendingRequestStore.KIND_EXTRACT, listOf("/tmp/a.zip"), "/tmp/dst",
            extra = encodeExtractExtra("windows-1256", direct = true),
            selected = listOf("dir/", "dir/file.txt")
        )
        val stored = PendingRequestStore.holdAndRead(dir, token, "op-rt5")!!
        assertEquals(
            OpRequest.Extract(
                Paths.get("/tmp/a.zip"), Paths.get("/tmp/dst"), "windows-1256",
                listOf("dir/", "dir/file.txt"), "op-rt5", placeDirectly = true
            ),
            stored.toOpRequest("op-rt5")
        )
    }

    @Test fun persistedExtractRoundTripsAllDefaults() {
        val dir = root()
        val token = PendingRequestStore.write(dir, PendingRequestStore.KIND_EXTRACT, listOf("/tmp/a.zip"), "/tmp/dst")
        val stored = PendingRequestStore.holdAndRead(dir, token, "op-rt6")!!
        assertNull("no explicit selected must persist as null (all entries)", stored.selected)
        assertNull(stored.extra)
        assertEquals(
            OpRequest.Extract(Paths.get("/tmp/a.zip"), Paths.get("/tmp/dst"), "UTF-8", null, "op-rt6", placeDirectly = false),
            stored.toOpRequest("op-rt6")
        )
    }

    // ---------- extra-field codec (charset name + "#direct" flag in one string) ----------

    @Test fun extractExtraCodecEncodesCharsetAndFlag() {
        assertEquals("UTF-8", encodeExtractExtra("UTF-8", direct = false))
        assertEquals("UTF-8#direct", encodeExtractExtra("UTF-8", direct = true))
        assertEquals("windows-1256#direct", encodeExtractExtra("windows-1256", direct = true))
    }

    @Test fun extractExtraCodecDecodesCharsetAndFlag() {
        assertEquals("UTF-8", decodeExtractCharset(encodeExtractExtra("UTF-8", direct = false)))
        assertEquals("windows-1256", decodeExtractCharset(encodeExtractExtra("windows-1256", direct = true)))
        assertTrue(decodeExtractDirect(encodeExtractExtra("windows-1256", direct = true)))
        assertFalse(decodeExtractDirect(encodeExtractExtra("UTF-8", direct = false)))
    }

    @Test fun extractExtraCodecDefaultsBlankAndNullToUtf8AndNoDirect() {
        assertEquals("UTF-8", decodeExtractCharset(null))
        assertEquals("UTF-8", decodeExtractCharset(""))
        assertEquals("UTF-8", decodeExtractCharset("   "))
        assertFalse(decodeExtractDirect(null))
        assertFalse(decodeExtractDirect("windows-1256"))
    }

    @Test fun encodeMapsStoreBackedKindsToExactStoredFields() {
        assertEquals(
            StoredOpRequest(PendingRequestStore.KIND_COPY, listOf("/tmp/a.txt", "/tmp/b.txt"), "/tmp/dst"),
            OpRequest.Copy(listOf(Paths.get("/tmp/a.txt"), Paths.get("/tmp/b.txt")), Paths.get("/tmp/dst"), "op-enc1").toStoredOpRequest()
        )
        assertEquals(
            StoredOpRequest(PendingRequestStore.KIND_MOVE, listOf("/tmp/a.txt"), "/tmp/dst"),
            OpRequest.Move(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "op-enc2").toStoredOpRequest()
        )
        assertEquals(
            StoredOpRequest(PendingRequestStore.KIND_DELETE, listOf("/tmp/a.txt", "/tmp/b.txt"), null),
            OpRequest.Delete(listOf(Paths.get("/tmp/a.txt"), Paths.get("/tmp/b.txt")), "op-enc3").toStoredOpRequest()
        )
        assertEquals(
            StoredOpRequest(PendingRequestStore.KIND_COMPRESS, listOf("/tmp/a.txt"), "/tmp/dst", extra = "arch.zip"),
            OpRequest.Compress(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "arch.zip", "op-enc4").toStoredOpRequest()
        )
        assertEquals(
            StoredOpRequest(PendingRequestStore.KIND_EXTRACT, listOf("/tmp/a.zip"), "/tmp/dst", extra = "UTF-8#direct", selected = listOf("dir/")),
            OpRequest.Extract(Paths.get("/tmp/a.zip"), Paths.get("/tmp/dst"), "UTF-8", listOf("dir/"), "op-enc5", placeDirectly = true).toStoredOpRequest()
        )
    }

    @Test fun encodeReturnsNullForResume() {
        assertNull(OpRequest.Resume("op-enc6").toStoredOpRequest())
    }

    @Test fun encodeDecodeRoundTripPreservesEveryField() {
        val copy = OpRequest.Copy(listOf(Paths.get("/tmp/a.txt"), Paths.get("/tmp/b.txt")), Paths.get("/tmp/dst"), "op-enc7")
        assertEquals(copy, copy.toStoredOpRequest()!!.toOpRequest("op-enc7"))

        val move = OpRequest.Move(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "op-enc8")
        assertEquals(move, move.toStoredOpRequest()!!.toOpRequest("op-enc8"))

        val delete = OpRequest.Delete(listOf(Paths.get("/tmp/a.txt")), "op-enc9")
        assertEquals(delete, delete.toStoredOpRequest()!!.toOpRequest("op-enc9"))

        val compress = OpRequest.Compress(listOf(Paths.get("/tmp/a.txt")), Paths.get("/tmp/dst"), "arch.zip", "op-enc10")
        assertEquals(compress, compress.toStoredOpRequest()!!.toOpRequest("op-enc10"))

        val extract = OpRequest.Extract(Paths.get("/tmp/a.zip"), Paths.get("/tmp/dst"), "windows-1256", listOf("x/", "y"), "op-enc11", placeDirectly = true)
        assertEquals(extract, extract.toStoredOpRequest()!!.toOpRequest("op-enc11"))

        val extractDefaults = OpRequest.Extract(Paths.get("/tmp/a.zip"), Paths.get("/tmp/dst"), "UTF-8", null, "op-enc12")
        assertEquals(extractDefaults, extractDefaults.toStoredOpRequest()!!.toOpRequest("op-enc12"))
    }
}
