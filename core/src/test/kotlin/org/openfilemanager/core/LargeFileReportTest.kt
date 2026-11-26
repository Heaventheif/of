package org.openfilemanager.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LargeFileReportTest {

    @Test
    fun reportKotlinFilesOverEightHundredLines() {
        val module = File(System.getProperty("user.dir"))
        val root = if (module.name == "core" || module.name == "app") module.parentFile else module
        assertTrue("project root is missing: ${root.absolutePath}", root.isDirectory)

        val large = root.walkTopDown()
            .onEnter {
                it.name != "build" && it.name != ".gradle" && it.name != ".git" &&
                    it.name != ".kotlin" && it.name != "tools" && it.name != "upload" &&
                    it.name != "download" && it.name != "skills"
            }
            .filter { it.isFile && it.extension == "kt" }
            .map { file -> file to file.readLines().size }
            .filter { (_, lines) -> lines > 800 }
            .sortedByDescending { (_, lines) -> lines }
            .toList()

        println("[phase0] Kotlin files over 800 lines: ${large.size}")
        large.forEach { (file, lines) ->
            println("[phase0] $lines\t${file.relativeTo(root)}")
        }
    }
}
