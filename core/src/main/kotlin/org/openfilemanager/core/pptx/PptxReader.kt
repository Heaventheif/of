package org.openfilemanager.core.pptx

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** سبب تعذّر فتح ملف عرض تقديمي. */
class PptxException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** ليس ملف ZIP (ليس PPTX/OOXML). */
        NOT_OOXML,
        /** العرض محمي بكلمة مرور (حاوية OLE بدل ZIP). */
        ENCRYPTED,
        /** البنية الداخلية تالفة أو ناقصة. */
        CORRUPT,
        /** لا شرائح إطلاقاً. */
        EMPTY,
        /** يتجاوز حدود القارئ المفروضة. */
        TOO_LARGE
    }
}

/** فقرة داخل شكل: [level] عمق التعداد (0 = المستوى الأول)، [bullet] هل تُعرض كنقطة تعداد. */
data class SlideParagraph(val text: String, val level: Int, val bullet: Boolean)

/** محتوى شكل واحد داخل الشريحة. */
sealed class SlideBlock {
    class Text(val paragraphs: List<SlideParagraph>) : SlideBlock()
    class Table(val rows: List<List<String>>) : SlideBlock()
}

data class Slide(
    val number: Int,
    /** عنوان الشريحة، أو "" إن لم تحدِّد الملف عنواناً. */
    val title: String,
    val blocks: List<SlideBlock>,
    /** ملاحظات المحاضر، إن وُجدت. */
    val notes: String,
    /** شريحة مخفية في العرض الأصلي (تُعرض مع علامة). */
    val hidden: Boolean
) {
    /** كل نص الشريحة للبحث (بدون حالة). */
    val searchText: String by lazy {
        val sb = StringBuilder(title).append('\n').append(notes).append('\n')
        for (b in blocks) when (b) {
            is SlideBlock.Text -> for (p in b.paragraphs) sb.append(p.text).append('\n')
            is SlideBlock.Table -> for (r in b.rows) for (c in r) sb.append(c).append('\n')
        }
        sb.toString()
    }
}

class Presentation(val slides: List<Slide>) {
    /** فهارس الشرائح (من 0) التي تحمل [query]، بلا حالة أحرف. */
    fun find(query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return slides.indices.filter { slides[it].searchText.contains(q, ignoreCase = true) }
    }
}

/**
 * قارئ PPTX نصّي خالص (بلا أندرويد): يقرأ حاوية OOXML مباشرة ويستخرج ترتيب الشرائح
 * وعناوينها ونصوصها وجداولها وملاحظاتها. لا يفك ضغط الوسائط ولا يعرض التصميم
 * الأصلي، فالكلفة ذاكرة صغيرة مهما كان حجم الصور في الملف.
 */
object PptxReader {
    const val MAX_FILE_BYTES = 80L * 1024 * 1024
    private const val MAX_TOTAL_CHARS = 40_000_000
    private const val MAX_PART_BYTES = 24L * 1024 * 1024
    private const val MAX_SLIDES = 3000
    private val SLIDE_PART = Regex("""(?i)^ppt/slides/slide\d+\.xml$""")

    fun parse(file: File): Presentation {
        if (file.length() > MAX_FILE_BYTES) throw PptxException(PptxException.Reason.TOO_LARGE)
        val head = ByteArray(4)
        val got = file.inputStream().use { s -> var k = 0; while (k < 4) { val r = s.read(head, k, 4 - k); if (r < 0) break; k += r }; k }
        if (got >= 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()) {
            // حاوية OLE: إما عرض OOXML مشفر (يحمل مجرى EncryptedPackage) أو ملف قديم
            // (مثل .ppt الثنائي) أُعيدت تسميته إلى .pptx — والتمييز بينهما من الدليل.
            val dirHead = file.inputStream().use { s ->
                val b = ByteArray(64 * 1024)
                var k = 0
                while (k < b.size) {
                    val r = s.read(b, k, b.size - k)
                    if (r < 0) break
                    k += r
                }
                String(b, 0, k, Charsets.UTF_16LE)
            }
            throw PptxException(
                if ("EncryptedPackage" in dirHead) PptxException.Reason.ENCRYPTED else PptxException.Reason.NOT_OOXML
            )
        }
        if (got < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            throw PptxException(PptxException.Reason.NOT_OOXML)
        }
        try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("[Content_Types].xml") == null) throw PptxException(PptxException.Reason.NOT_OOXML)
                return parse(zip)
            }
        } catch (e: PptxException) {
            throw e
        } catch (e: Exception) {
            // ZIP تالف أو مدخل ناقص: رسالة واحدة مفهومة بدل انهيار
            throw PptxException(PptxException.Reason.CORRUPT)
        }
    }

    /** يقرأ من حاوية مفتوحة (يفترض أن المستدعي تحقق أنها OOXML). */
    fun parse(zip: ZipFile): Presentation {
        val budget = Budget()
        val rels = read(zip, "ppt/_rels/presentation.xml.rels", budget)?.let(::readRelationships) ?: emptyMap()
        val pres = read(zip, "ppt/presentation.xml", budget)
        var targets: List<String> = emptyList()
        // 1) الترتيب الذي كتبه المؤلف في قائمة الشرائح
        if (pres != null) {
            val ids: List<String> = MiniXml.parse(pres).descendants("p:sldId").mapNotNull { it.attr("id") }
            targets = ids.mapNotNull { id: String -> rels[id]?.takeIf { it.type.endsWith("/slide") }?.resolved("ppt") }
        }
        // 2) ملف بلا قائمة: شرائح العلاقات بأرقام أجزائها
        if (targets.isEmpty()) {
            val fromRels: List<Relationship> = rels.values.filter { it.type.endsWith("/slide") }
            targets = fromRels.map { it.resolved("ppt") }.sortedBy { partNumber(it) }
        }
        // 3) ملف بلا علاقات: أجزاء الشرائح في الحاوية نفسها
        if (targets.isEmpty()) {
            val names = ArrayList<String>()
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val n = en.nextElement().name
                if (SLIDE_PART.matches(n)) names.add(n)
            }
            targets = names.sortedBy { partNumber(it) }
        }
        val unique = targets.distinct()
        if (unique.isEmpty()) throw PptxException(PptxException.Reason.EMPTY)
        val slides = ArrayList<Slide>(minOf(unique.size, MAX_SLIDES))
        for ((i, t) in unique.withIndex()) {
            if (i >= MAX_SLIDES) break
            val xml = read(zip, t, budget) ?: continue
            val slide = try {
                parseSlide(xml, i + 1)
            } catch (e: PptxException) {
                throw e
            } catch (e: Exception) {
                throw PptxException(PptxException.Reason.CORRUPT)
            }
            val notes = slideNotes(zip, t, budget)
            val s = if (notes.isEmpty()) slide else slide.copy(notes = notes)
            budget.text += s.searchText.length
            if (budget.text > MAX_TOTAL_CHARS) throw PptxException(PptxException.Reason.TOO_LARGE)
            slides.add(s)
        }
        if (slides.isEmpty()) throw PptxException(PptxException.Reason.EMPTY)
        return Presentation(slides)
    }

    // ---- جزء الشريحة ----------------------------------------------------------------------------------------

    fun parseSlide(xml: String, number: Int): Slide {
        val sld = MiniXml.parse(xml).children.firstOrNull() ?: throw PptxException(PptxException.Reason.CORRUPT)
        val hidden = sld.attr("show") == "0"
        val tree = sld.descendants("p:spTree").firstOrNull()
            ?: return Slide(number, "", emptyList(), "", hidden)
        var title = ""
        val blocks = ArrayList<SlideBlock>()
        fun walk(node: XNode) {
            for (c in node.children) {
                when {
                    c.isElement("p:sp") -> {
                        val ph = c.descendants("p:ph").firstOrNull()?.attr("type")
                        val paras = paragraphs(c.first("p:txBody"))
                        if (paras.none { it.text.isNotBlank() }) continue
                        when (ph) {
                            "title", "ctrTitle" -> if (title.isEmpty()) title = paras.joinToString(" ") { it.text.trim() }
                            "dt", "sldNum", "ftr", "hdr" -> Unit
                            else -> blocks.add(SlideBlock.Text(paras))
                        }
                    }
                    c.isElement("p:graphicFrame") -> {
                        val tbl = c.descendants("a:tbl").firstOrNull()
                        if (tbl != null) {
                            val rows = tbl.children("a:tr").map { tr -> tr.children("a:tc").map(::cellText) }
                            if (rows.any { r -> r.any { it.isNotBlank() } }) blocks.add(SlideBlock.Table(rows))
                        }
                    }
                    else -> walk(c) // الأشكال داخل مجموعة (p:grpSp) تُقرأ بترتيبها
                }
            }
        }
        walk(tree)
        return Slide(number, title, blocks, "", hidden)
    }

    private fun paragraphs(txBody: XNode?): List<SlideParagraph> {
        val body = txBody ?: return emptyList()
        return body.children("a:p").mapNotNull { p ->
            val text = textWithBreaks(p).replace('\u00A0', ' ').trim()
            if (text.isEmpty()) null
            else SlideParagraph(
                text, (p.first("a:pPr")?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8),
                p.descendants("a:buNone").isEmpty()
            )
        }
    }

    private fun cellText(tc: XNode): String =
        tc.children("a:txBody").flatMap { paragraphs(it) }.joinToString("\n") { it.text }

    /** نص الفقرة: كل <a:t> مع فواصل الأسطر <a:br>. */
    private fun textWithBreaks(p: XNode): String {
        val sb = StringBuilder()
        fun walk(n: XNode) {
            for (c in n.children) when {
                c.isElement("a:t") -> sb.append(c.text)
                c.isElement("a:br") -> sb.append('\n')
                else -> walk(c)
            }
        }
        walk(p)
        return sb.toString()
    }

    private fun slideNotes(zip: ZipFile, slidePart: String, budget: Budget): String {
        val dir = slidePart.substringBeforeLast('/', "")
        val relName = "$dir/_rels/${slidePart.substringAfterLast('/')}.rels"
        val rels = read(zip, relName, budget)?.let(::readRelationships) ?: return ""
        val target = rels.values.firstOrNull { it.type.endsWith("/notesSlide") }?.resolved(dir) ?: return ""
        val xml = read(zip, target, budget) ?: return ""
        val sb = StringBuilder()
        for (sp in MiniXml.parse(xml).descendants("p:sp")) {
            if (sp.descendants("p:ph").firstOrNull()?.attr("type") == "sldNum") continue
            val t = sp.first("p:txBody")?.let { paragraphs(it) }?.filter { it.text.isNotBlank() } ?: continue
            for (p in t) sb.append(p.text).append('\n')
        }
        return sb.toString().trim()
    }

    // ---- الأجزاء والعلاقات -----------------------------------------------------------------------------------

    private class Relationship(val type: String, private val target: String) {
        fun resolved(baseDir: String): String {
            if (target.startsWith("/")) return target.substring(1)
            val parts = ArrayList<String>()
            for (seg in baseDir.split('/')) if (seg.isNotEmpty() && seg != ".") parts.add(seg)
            for (seg in target.split('/')) when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
            return parts.joinToString("/")
        }
    }

    private fun readRelationships(xml: String): Map<String, Relationship> {
        val out = HashMap<String, Relationship>()
        for (r in MiniXml.parse(xml).descendants("Relationship")) {
            val id = r.attr("id") ?: continue
            // العلاقات الخارجية (الروابط) لا تشير إلى جزء داخل الحاوية
            if (r.attr("TargetMode") == "External") continue
            out[id] = Relationship(r.attr("Type").orEmpty(), r.attr("Target").orEmpty())
        }
        return out
    }

    private fun partNumber(name: String): Int =
        Regex("(\\d+)(?=\\.[^.]+$)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private class Budget { var text = 0 }

    private fun read(zip: ZipFile, name: String, budget: Budget): String? {
        val e = zip.getEntry(name) ?: return null
        if (e.size > MAX_PART_BYTES) throw PptxException(PptxException.Reason.TOO_LARGE)
        return zip.getInputStream(e).use(::readText)
    }

    private fun readText(s: InputStream): String {
        // قراءة محدودة: لا يُحمَّل جزء أكبر من الحد ولو كذب الترويسة عن الحجم
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_PART_BYTES) throw PptxException(PptxException.Reason.TOO_LARGE)
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
