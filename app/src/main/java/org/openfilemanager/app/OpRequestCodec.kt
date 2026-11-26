package org.openfilemanager.app

import java.nio.file.Paths

/** Encode direction, moved verbatim from the per-kind branches of FileOperationService.enqueue. */
internal fun OpRequest.toStoredOpRequest(): StoredOpRequest? = when (this) {
    is OpRequest.Copy -> StoredOpRequest(
        PendingRequestStore.KIND_COPY,
        sources.map { it.toString() }, targetDir.toString()
    )
    is OpRequest.Move -> StoredOpRequest(
        PendingRequestStore.KIND_MOVE,
        sources.map { it.toString() }, targetDir.toString()
    )
    is OpRequest.Delete -> StoredOpRequest(
        PendingRequestStore.KIND_DELETE,
        sources.map { it.toString() }, null
    )
    is OpRequest.Compress -> StoredOpRequest(
        PendingRequestStore.KIND_COMPRESS,
        sources.map { it.toString() }, targetDir.toString(),
        extra = targetName
    )
    is OpRequest.Extract -> StoredOpRequest(
        PendingRequestStore.KIND_EXTRACT,
        listOf(archive.toString()), targetDir.toString(),
        extra = encodeExtractExtra(charsetName, placeDirectly),
        selected = selected
    )
    is OpRequest.Resume -> null
}

/**
 * يحوّل الطلب المخزّن إلى OpRequest بنفس مفاتيح decode تماماً —
 * مسار الاسترداد يعيد بناء الطلب كما لو وصلت نيته الآن. امتداد علوية كي
 * تستدعيها اختبارات الوحدة مباشرة.
 */
internal fun StoredOpRequest.toOpRequest(opId: String): OpRequest? {
    // جسم كتلي: return المبكر داخل جسم تعبيرية ممنوع في Kotlin.
    if (kind == PendingRequestStore.KIND_DELETE) {
        return OpRequest.Delete(paths.map { Paths.get(it) }, opId)
    }
    if (kind == PendingRequestStore.KIND_COMPRESS) {
        val dir = target ?: return null
        return OpRequest.Compress(paths.map { Paths.get(it) }, Paths.get(dir), extra ?: "", opId)
    }
    if (kind == PendingRequestStore.KIND_EXTRACT) {
        val dir = target ?: return null
        return OpRequest.Extract(
            Paths.get(paths.first()), Paths.get(dir),
            decodeExtractCharset(extra), selected, opId, decodeExtractDirect(extra)
        )
    }
    val dir = target ?: return null
    return when (kind) {
        PendingRequestStore.KIND_COPY -> OpRequest.Copy(paths.map { Paths.get(it) }, Paths.get(dir), opId)
        PendingRequestStore.KIND_MOVE -> OpRequest.Move(paths.map { Paths.get(it) }, Paths.get(dir), opId)
        else -> null
    }
}

/** ترميز علم «الوضع المباشر» داخل حقل extra المخزّن (يحفظ صيغة التخزين كما هي). */
internal fun encodeExtractExtra(charsetName: String, direct: Boolean): String =
    if (direct) "$charsetName#direct" else charsetName

internal fun decodeExtractCharset(extra: String?): String =
    extra?.substringBefore('#')?.ifBlank { null } ?: Charsets.UTF_8.name()

internal fun decodeExtractDirect(extra: String?): Boolean = extra?.endsWith("#direct") == true
