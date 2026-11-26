package org.openfilemanager.core.pptx

/**
 * محلّل XML صغير للاستعمال الداخلي (شجرة في الذاكرة) لملفات العرض التقديمي.
 * يكفي لـ OOXML: العناصر والخصائص والنصوص وكيانات XML و<![CDATA[. لا يتحقق من
 * الصحة الدقيقة ولا يعالج DTD (تُتخطّى ولا تُوسَّع أبداً، فلا هجمات "مليار ضحكة").
 */
internal class XNode(val name: String) {
    /** الخصائص باسمها المحلي الصغير (بعد النقطتين) لا بالبادئة: XML لا يفرّق حالة أسماء الخصائص عملياً هنا. */
    val attrs = HashMap<String, String>(4)
    val children = ArrayList<XNode>(4)
    val text = StringBuilder()
    /** العقدة الأم — لا تُضبط إلا داخل [MiniXml.parse]. */
    var parent: XNode? = null
    internal set

    private val localName: String = name.substringAfter(':')

    /** هل اسم العنعة مطابق؟ يقبل الاسم المؤهَّل كما كُتب (a:p) أو غير المؤهَّل (p). */
    fun isElement(qualified: String): Boolean = name == qualified || (!name.contains(':') && localName == qualified.substringAfter(':'))

    fun attr(local: String): String? = attrs[local.lowercase()]

    /** الأبناء المباشرون بالاسم. */
    fun children(named: String): List<XNode> = children.filter { it.isElement(named) }

    fun first(named: String): XNode? = children.firstOrNull { it.isElement(named) }

    /** كل الأحفاد بالاسم بعمق أي، بترتيب المستند. */
    fun descendants(named: String): List<XNode> {
        val out = ArrayList<XNode>()
        fun walk(n: XNode) {
            for (c in n.children) {
                if (c.isElement(named)) out.add(c)
                walk(c)
            }
        }
        walk(this)
        return out
    }

}

internal object MiniXml {
    private const val MAX_DEPTH = 300

    fun parse(src: String): XNode {
        val root = XNode("#document")
        var cur = root
        var depth = 0
        var i = 0
        val n = src.length
        val text = StringBuilder()
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) { text.append(src, i, n); break }
            text.append(src, i, lt)
            if (src.startsWith("<!--", lt)) {
                i = src.indexOf("-->", lt + 4).let { if (it < 0) n else it + 3 }
            } else if (src.startsWith("<![CDATA[", lt)) {
                val end = src.indexOf("]]>", lt + 9)
                val stop = if (end < 0) n else end
                text.append(src, lt + 9, stop)
                i = if (end < 0) n else end + 3
            } else if (src.startsWith("<?", lt)) {
                i = src.indexOf("?>", lt + 2).let { if (it < 0) n else it + 2 }
            } else if (src.startsWith("<!", lt)) {
                i = src.indexOf('>', lt + 2).let { if (it < 0) n else it + 1 }
            } else if (src.startsWith("</", lt)) {
                flushText(cur, text)
                val gt = src.indexOf('>', lt + 2)
                if (gt < 0) break
                // أنزل إلى العقدة المطابقة: تسمح بوسم إغلاق زائد دون فقد الشجرة
                var p: XNode? = cur
                val closeName = src.substring(lt + 2, gt).trim()
                while (p != null && p.name != "#document" && p.name != closeName) p = p.parent
                if (p != null && p.name != "#document") cur = p.parent ?: root
                if (depth > 0) depth--
                i = gt + 1
            } else {
                flushText(cur, text)
                val gt = findTagEnd(src, lt + 1)
                if (gt < 0) break
                val body = src.substring(lt + 1, gt)
                val selfClosing = body.endsWith("/")
                val tag = if (selfClosing) body.dropLast(1) else body
                val sp = tag.indexOfFirst { it.isWhitespace() }
                val node = XNode(if (sp < 0) tag else tag.substring(0, sp))
                if (sp >= 0) readAttrs(tag, sp, node.attrs)
                if (depth >= MAX_DEPTH) throw PptxException(PptxException.Reason.CORRUPT)
                cur.children.add(node)
                node.parent = cur
                if (!selfClosing) { cur = node; depth++ }
                i = gt + 1
            }
        }
        flushText(cur, text)
        return root
    }

    private fun findTagEnd(s: String, from: Int): Int {
        var q = ' '
        for (i in from until s.length) {
            val c = s[i]
            if (q != ' ') { if (c == q) q = ' ' } else when (c) { '"', '\'' -> q = c; '>' -> return i }
        }
        return -1
    }

    private fun readAttrs(tag: String, from: Int, out: MutableMap<String, String>) {
        for (m in ATTR.findAll(tag)) {
            if (m.range.first >= from) {
                out[m.groupValues[1].substringAfter(":").lowercase()] = decode(m.groupValues[2].ifEmpty { m.groupValues[3] })
            }
        }
    }

    private fun flushText(into: XNode, sb: StringBuilder) {
        if (sb.isNotEmpty()) {
            val t = sb.toString()
            into.text.append(if (t.indexOf('&') >= 0) decode(t) else t)
            sb.setLength(0)
        }
    }

    private val ATTR = Regex("([\\w.:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    private val ENTITIES = mapOf(
        "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'",
        // كيانات شائعة في ملفات وُلِّدت ببرامج غير PowerPoint (ليست معرَّفة في XML دون DTD)
        "nbsp" to "\u00A0", "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "middot" to "·", "times" to "×"
    )

    fun decode(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = s.indexOf(';', i + 1)
            val name = if (semi in (i + 2)..(i + 10)) s.substring(i + 1, semi) else null
            when {
                name == null -> { sb.append(c); i++ }
                name.startsWith("#x") || name.startsWith("#X") -> {
                    val v = name.substring(2).toIntOrNull(16)
                    if (v == null || !Character.isValidCodePoint(v)) sb.append('\uFFFD') else sb.appendCodePoint(v)
                    i = semi + 1
                }
                name.startsWith("#") -> {
                    val v = name.substring(1).toIntOrNull()
                    if (v == null || !Character.isValidCodePoint(v)) sb.append('\uFFFD') else sb.appendCodePoint(v)
                    i = semi + 1
                }
                ENTITIES[name] != null -> { sb.append(ENTITIES[name]); i = semi + 1 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
