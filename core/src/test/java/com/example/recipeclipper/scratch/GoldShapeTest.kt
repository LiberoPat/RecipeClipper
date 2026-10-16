package com.example.recipeclipper.scratch

import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.Aisles
import com.example.recipeclipper.data.model.IngredientDensities
import com.example.recipeclipper.data.model.IngredientName
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.remote.JsonLdRecipeParser
import java.io.File
import org.junit.Test

// Scratch only (#271), never committed: which gold ingredient lines have no digit, and what the tables say about them.
class GoldShapeTest {
    @Test fun dump() {
        val dir = File("/private/tmp/claude-502/-Users-oli-Downloads-RecipeClipper/16fed9c5-8eca-43ea-a868-c9bccb27aa0e/scratchpad/wl-pages")
        val ld = Regex("""<script[^>]*application/ld\+json[^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val out = StringBuilder()
        val lines = mutableListOf<String>()
        for (entry in File(dir, "index.txt").readLines()) {
            val parts = entry.split(" ")
            if (parts.size < 3 || parts[1] != "200") continue
            val html = File(dir, "%02d.html".format(parts[0].toInt())).readText()
            val recipe = JsonLdRecipeParser.parse(ld.findAll(html).map { it.groupValues[1] }.toList(), parts[2]) ?: continue
            lines += recipe.ingredients
        }
        File("/Users/oli/Downloads/RecipeClipper/.claude/worktrees/agent-a94e3be17ee1c0b28/tools/eval/gold/ingredients.jsonl").readLines()
            .forEach { lines += it.substringAfter("[\"").substringBefore("\",") }
        val w = LanguageWords.ENGLISH
        for (line in lines.distinct()) {
            if (line.any { it.isDigit() } || line.any { it in "¼½¾⅓⅔⅛⅜⅝⅞" }) continue
            val name = IngredientName.of(line, w)
            val aisle = name?.let { Aisles.ofName(it, w) } ?: Aisle.OTHER
            val density = IngredientDensities.find(line, w) != null
            out.append("$line\tname=$name\taisle=$aisle\tdensity=$density\n")
        }
        File(dir, "gold-nodigit.tsv").writeText(out.toString())
    }
}
