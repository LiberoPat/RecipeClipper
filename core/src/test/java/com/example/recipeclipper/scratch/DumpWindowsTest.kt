package com.example.recipeclipper.scratch

import com.example.recipeclipper.data.model.RecipeTextWindow
import com.example.recipeclipper.data.remote.PageTextReader
import java.io.File
import org.junit.Test

// Scratch only (#271), never committed: the harness's windows, for reading.
class DumpWindowsTest {
    @Test fun dump() {
        val dir = File("/private/tmp/claude-502/-Users-oli-Downloads-RecipeClipper/16fed9c5-8eca-43ea-a868-c9bccb27aa0e/scratchpad/wl-pages")
        val out = File(dir, System.getProperty("windows.out") ?: "windows")
        out.mkdirs()
        for (entry in File(dir, "index.txt").readLines()) {
            val parts = entry.split(" ")
            if (parts.size < 3 || parts[1] != "200") continue
            val f = File(dir, "%02d.html".format(parts[0].toInt()))
            val page = PageTextReader.read(f.readText(), parts[2])
            val w = RecipeTextWindow.window(page, (4096 - 1800) * 3) ?: "(none)"
            File(out, "%02d.txt".format(parts[0].toInt())).writeText(parts[2] + "\n=====\n" + w)
            val trimmed = page.lines.map { it.trim() }.filter { it.isNotEmpty() }
            val a = RecipeTextWindow.anchor(trimmed)
            File(out, "%02d.lines".format(parts[0].toInt())).writeText("anchor $a: ${a?.let { trimmed[it] }}\n" + trimmed.joinToString("\n"))
        }
    }
}
