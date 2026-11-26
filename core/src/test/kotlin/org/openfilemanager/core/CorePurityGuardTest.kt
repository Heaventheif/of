package org.openfilemanager.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

class CorePurityGuardTest {

    private fun locateCoreMain(): Path {
        // Gradle runs JVM-module tests with working dir = module dir (core/).
        val fromModule = Paths.get("src/main")
        if (Files.isDirectory(fromModule)) return fromModule
        // IDE/other working dirs: fall back to repo layout.
        val fromRoot = Paths.get("core/src/main")
        if (Files.isDirectory(fromRoot)) return fromRoot
        throw IllegalStateException("core/src/main not found from working dir " + Paths.get("").toAbsolutePath())
    }

    private fun walkSources(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream.filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }.toList()
        }

    @Test
    fun coreProductionSourcesImportNoAndroid() {
        val main = locateCoreMain()
        val offenders = walkSources(main).flatMap { file ->
            Files.readAllLines(file).mapIndexedNotNull { idx, line ->
                val trimmed = line.trim()
                val isForbidden =
                    // إضافة androidx وkotlin.reflect للحظر: core وحدة JVM خالصة — androidx سيكسر اختباراتها، وkotlin.reflect يجرّ مكتبة ثقيلة بلا حاجة.
                    trimmed.startsWith("import android.") ||
                        trimmed.startsWith("import androidx.") ||
                        trimmed.startsWith("import kotlin.reflect.") ||
                        trimmed.startsWith("import org.openfilemanager.app.") ||
                        trimmed == "import org.openfilemanager.app"
                if (isForbidden) "${file.fileName}:$idx ${trimmed}" else null
            }
        }
        assertTrue(
            "core must not depend on Android/AndroidX/kotlin-reflect or on :app. Offending imports:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun coreProductionSourcesReferenceNoAppPackage() {
        val main = locateCoreMain()
        val offenders = walkSources(main).flatMap { file ->
            Files.readAllLines(file).mapIndexedNotNull { idx, line ->
                // Fully-qualified references without import, e.g. org.openfilemanager.app.Foo.bar()
                if (line.contains("org.openfilemanager.app") && !file.toString().endsWith("CorePurityGuardTest.kt"))
                    "${file.fileName}:$idx ${line.trim()}"
                else null
            }
        }
        assertTrue(
            "core must not reference the :app package. Offending lines:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** منع النصوص العربية في كود `core` الإنتاجي: المحرك يجب أن يُرجع رموزاً (CoreOpCode) لا نصوصاً مترجمة، فالترجمة مسؤولية طبقة التطبيق عبر strings.xml. */
    @Test
    fun coreProductionSourcesContainNoArabicStringLiterals() {
        val main = locateCoreMain()
        val arabicRange = '\u0600'..'\u06FF'
        // تعزيز الفحص لتغطية: - النصوص العادية: "..." (مع علامات اقتباس مهربة \") - النصوص متعددة الأسطر: """...""" (triple-quoted raw strings) كان الفحص…
        val singleLineString = Regex("(?<!\")\"([^\"\\\\\\n]*(?:\\\\.[^\"\\\\\\n]*)*)\"(?!\")")
        val multilineString = Regex("\"\"\"([^\"]*)\"\"\"")
        val offenders = walkSources(main).flatMap { file ->
            // Skip PathRedactor — its regex patterns legitimately contain Arabic punctuation
            if (file.fileName.toString() == "PathRedactor.kt") return@flatMap emptyList()
            // Strip block comments (/* ... */ and /** ... */) then line comments.
            // Arabic in comments is documentation, not production data.
            val raw = Files.readString(file)
            val withoutBlockComments = raw.replace(Regex("/\\*[*]?[\\s\\S]*?\\*/", RegexOption.MULTILINE), "")
            val lines = withoutBlockComments.split("\n")
            // Pass 1: check single-line string literals (handles escaped quotes)
            val singleLineHits = lines.flatMapIndexed { idx, line ->
                val codePart = line.substringBefore("//")
                singleLineString.findAll(codePart).mapNotNull { m ->
                    val s = m.groupValues[1]
                    if (s.any { it in arabicRange }) "${file.fileName}:${idx + 1} (single) \"${s.take(60)}\"" else null
                }
            }
            // Pass 2: check multiline raw strings ("""...""")
            val multilineHits = multilineString.findAll(withoutBlockComments).mapNotNull { m ->
                val s = m.groupValues[1]
                if (s.any { it in arabicRange }) {
                    // Find which line it starts on
                    val prefix = withoutBlockComments.substring(0, m.range.first)
                    val lineNum = prefix.count { it == '\n' } + 1
                    "${file.fileName}:$lineNum (multiline) \"${s.take(60)}\""
                } else null
            }
            singleLineHits + multilineHits
        }
        assertTrue(
            "core must not contain Arabic string literals in production code. " +
                "Use CoreOpCode constants instead; move translations to app/strings.xml. " +
                "Checked single-line (with escape handling) and multi-line (\"\"\"...\"\"\") strings. " +
                "Offending literals:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
