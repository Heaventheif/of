package org.openfilemanager.app

import android.text.BidiFormatter

/**
 * أسماء الملفات المختلطة عربي/لاتيني: تغليف بـ BidiFormatter.unicodeWrap
 * في القوائم والحواريات والإشعارات، فلا تنعكس المقاطع اللاتينية داخل نص عربي ولا
 * تُقتطع علامات الترقيم المجاورة للأسماء.
 */
object BidiText {
    private val bidi = BidiFormatter.getInstance()

    fun wrap(name: String?): String =
        if (name.isNullOrEmpty()) "" else bidi.unicodeWrap(name)
}
