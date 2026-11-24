package org.openfilemanager.app.imageedit.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import org.openfilemanager.app.imageedit.model.EditorFontFamily

/**
 * تحميل خطوط المحرّر. الخطوط العربية المضمّنة تُقرأ من assets/fonts وتُخزَّن مؤقتاً؛
 * وإن فشل تحميل أي منها يُستخدم خط النظام بدل الانهيار.
 */
object EditorFonts {
    @Volatile private var assets: AssetManager? = null
    private val cache = HashMap<String, Typeface?>()

    fun init(ctx: Context) { assets = ctx.applicationContext.assets }

    private fun loadAsset(file: String): Typeface? = synchronized(cache) {
        cache.getOrPut(file) {
            val am = assets ?: return@getOrPut null
            runCatching { Typeface.createFromAsset(am, "fonts/$file") }.getOrNull()
        }
    }

    fun resolve(family: EditorFontFamily, bold: Boolean = false, italic: Boolean = false): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val regular = family.asset?.let(::loadAsset)
        if (regular != null) {
            val boldFace = if (bold) family.assetBold?.let(::loadAsset) else null
            return when {
                boldFace != null -> if (italic) Typeface.create(boldFace, Typeface.ITALIC) else boldFace
                style == Typeface.NORMAL -> regular
                else -> Typeface.create(regular, style) // عريض/مائل اصطناعي إن لم يتوفر ملف خاص
            }
        }
        val base = Typeface.create(family.systemName, Typeface.NORMAL)
        return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
    }
}
