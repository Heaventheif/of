package org.openfilemanager.core

enum class NameProblem { EMPTY, RESERVED, TOO_LONG, FORBIDDEN_CHAR, TRAILING_DOT_OR_SPACE }

/**
 * قواعد صحة أسماء الملفات.
 * الحد 255 بايت وليس 255 حرفاً: الحرف العربي بايتان في UTF-8.
 */
object NameRules {
    const val MAX_NAME_BYTES = 255
    private const val FAT_FORBIDDEN = "\\:*?\"<>|"

    /** يعيد null إن كان الاسم صالحاً، وإلا أول مشكلة وُجدت. */
    fun problem(name: String, strictFat: Boolean = true): NameProblem? {
        if (name.isBlank()) return NameProblem.EMPTY
        if (name == "." || name == "..") return NameProblem.RESERVED
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return NameProblem.TOO_LONG
        if (name.any { it == '/' || it == '\u0000' || it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }) return NameProblem.FORBIDDEN_CHAR // bidi overrides spoof extensions
        if (strictFat) {
            if (name.any { it in FAT_FORBIDDEN }) return NameProblem.FORBIDDEN_CHAR
            if (name.last() == '.' || name.last() == ' ') return NameProblem.TRAILING_DOT_OR_SPACE
        }
        return null
    }

    fun isValid(name: String, strictFat: Boolean = true): Boolean = problem(name, strictFat) == null
}
