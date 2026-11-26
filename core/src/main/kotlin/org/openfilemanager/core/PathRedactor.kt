package org.openfilemanager.core

import java.security.MessageDigest

/** تعمية المسارات في السجلات المحلية. */
object PathRedactor {

    /**
     * مكوّن مسار: أي محارف عدا المسافات وعلامات الحدود الصريحة (اقتباسات،
     * أقواس، فواصل، `=`، ومحارف المسارات نفسها). فئة سالبة تضم تلقائياً العربية
     * والرموز التعبيرية وامتدادات متعددة النقاط و`+` و`#` (أسماء ملفات مشروعة).
     */
    private const val COMPONENT = """[^\s"'`()\[\]{}<>,،؛;:!؟?=/\\|*]+"""

    /**
     * مسار يونكس: مرساة `/` مع مكوّنين فأكثر، والمسافة بين مكوّني الاسم الواحد
     * مسموحة (`My Photos`) حتى أول حد صريح .
     */
    private val unixPath = Regex("""(?:/$COMPONENT(?: $COMPONENT)*){2,}""")

    /**
     * (تحديث) مسار مطلق بمكوّن واحد (`/sdcard`، `/ملف.pdf`) كان ينجو من
     * التعمية (`{2,}`) فيظهر نصاً صريحاً في السجل — وهو مكشوف كالمسار الطويل.
     * هنا يُعمّى كذلك؛ البقاء على خطأ إنذار كاذب في الاختبار أفضل من كشف اسم ملف.
     */
    private val unixSinglePath = Regex("""/$COMPONENT(?: $COMPONENT)*""")

    /**
     * مسار ويندوز: حرف قرص + شرطة خلفية/أمامية، ثم run واحد مسطح (بلا تكميم
     * متشعّب فلا رجوع كارثي في regex) يضم المسافات والشرطات الخلفية حتى أول
     * حد صريح — المسافة داخل الاسم مسموحة .
     */
    private val windowsPath = Regex("""[A-Za-z]:[\\/](?:[^"'`()\[\]{}<>,،؛;:!؟?=/|*\n\r\t])+""")

    /** كاشف التحقق مستقل عن خوارزمية التعمية عمداً. */
    private val unixPathDetector = Regex("""(?:/\S+){1,}""")

    /** تشغيلة بشرطة خلفية بين نصين بلا مسافة (`Docs\ملف`، `C:\Users\...`). */
    private val windowsPathDetector = Regex("""\S+\\\S+""")
    private val tokenFormat = Regex("""#[0-9a-f]{12}""")

    /** رمز مستقر لمسار واحد: أول 12 خانة سادسة عشرية من SHA-256 للنص. */
    fun token(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return "#" + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** يستبدل كل مسار في الرسالة برمزه، ويترك ما سواه كما هو. ويندوز أولاً حتى لا يقطعها المسار اليونكس . */
    fun redact(message: String): String {
        val one = windowsPath.replace(message) { it.value.let(::token) }
        val two = unixPath.replace(one) { it.value.let(::token) }
        return unixSinglePath.replace(two) { it.value.let(::token) }
    }

    /** للتحقق في الاختبارات: هل النص لا يحوي مساراً صريحاً بعد التعمية؟ (كاشف أوسع من التعمية نفسها — انظر KDoc). */
    fun containsNoRawPath(redacted: String): Boolean =
        !unixPathDetector.containsMatchIn(redacted) && !windowsPathDetector.containsMatchIn(redacted)

    /** هل الرمز بالشكل المتوقع؟ (استخدام داخلي واختباري) */
    fun isToken(value: String): Boolean = tokenFormat.matches(value)
}
