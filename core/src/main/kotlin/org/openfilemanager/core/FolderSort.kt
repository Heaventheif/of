package org.openfilemanager.core

/** حقل الفرز المتاح لكل مجلد. */
enum class SortFieldCore { NAME, SIZE, MODIFIED, TYPE }
enum class SortDirectionCore { ASCENDING, DESCENDING }

data class FolderSort(
    val field: SortFieldCore = SortFieldCore.NAME,
    val direction: SortDirectionCore = SortDirectionCore.ASCENDING
) {
    fun encode(): String = "${field.name}:${direction.name}"

    companion object {
        fun decode(raw: String?): FolderSort? {
            if (raw == null) return null
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val field = runCatching { SortFieldCore.valueOf(parts[0]) }.getOrNull() ?: return null
            val direction = runCatching { SortDirectionCore.valueOf(parts[1]) }.getOrNull() ?: return null
            return FolderSort(field, direction)
        }
    }
}
