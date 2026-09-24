package com.example.recipeclipper.data.remote

import org.jsoup.Jsoup

/** What [RecipeTextSplitter] found in a block of text. The name comes from elsewhere
 *  (a Reddit post's title), so it isn't here. */
data class SplitRecipe(
    val ingredients: List<String>,
    val instructions: List<String>,
    val yield: String?,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?
)

/**
 * Turns free text that is laid out as a recipe (a Reddit post body, or a comment
 * transcribing a photo of a recipe card) into ingredients and steps.
 *
 * **It never guesses.** Text splits only when it carries its own structure: a line that is
 * nothing but an ingredients header ("Ingredients", "**Ingredients:**", "## You'll need")
 * and a line that is nothing but an instructions header ("Directions", "Method", "Steps").
 * Every line under the first becomes an ingredient, every line under the second a step, each
 * as written. Without both, or with either section empty, the answer is null: a paragraph of
 * prose that happens to mention flour is not a recipe. Sections may repeat (a sauce, then a
 * dough) and come in either order; lines under a notes header ("Notes", "Tips", "Edit:") are
 * dropped, as is everything before the first header (the story), except a labelled yield
 * ("Serves 4") or time ("Prep time: 10 min").
 *
 * Reddit bodies are Markdown, so each line loses its list marker, heading hashes, emphasis,
 * link syntax and backslash escapes, then goes through the same `stripHtml` as every other
 * extracted string. Steps are one per line, as a plain-string `recipeInstructions` is.
 *
 * Pure: text in, data out. The iOS port (`RecipeTextSplitter.swift`) follows it line for line.
 */
object RecipeTextSplitter {

    enum class Section { INGREDIENTS, INSTRUCTIONS, END }

    private val INGREDIENT_HEADERS = setOf(
        "ingredients", "ingredient", "ingredient list", "ingredients list",
        "you will need", "you'll need", "what you need", "what you'll need", "what you will need",
    )
    private val INSTRUCTION_HEADERS = setOf(
        "instructions", "directions", "method", "steps", "preparation", "procedure",
        "how to make", "how to make it",
    )
    private val END_HEADERS = setOf(
        "notes", "note", "recipe notes", "tips", "tip", "nutrition", "nutrition facts",
        "source", "sources",
    )

    /** "Ingredients for the cake:" is a header only with its colon; without one the line is
     *  more likely a sentence ("Ingredients for this are cheap"). */
    private val FOR_HEADER = Regex("^(ingredients|instructions|directions|method)\\s+for\\s+.+:$")

    /** "Edit:", "EDIT 2:", "Update:" and the like start a trailing note, never a step. */
    private val EDIT_LINE = Regex("^(?:edit|update|eta)\\b[^:]{0,12}:", RegexOption.IGNORE_CASE)

    private val TRAILING_PARENTHETICAL = Regex("\\s*\\([^)]*\\)$")

    /** The section a cleaned line opens, or null for an ordinary line. */
    fun section(line: String): Section? {
        if (EDIT_LINE.containsMatchIn(line)) return Section.END
        val lower = line.lowercase().trim()
        if (FOR_HEADER.matches(lower)) {
            return if (lower.startsWith("ingredients")) Section.INGREDIENTS else Section.INSTRUCTIONS
        }
        val base = TRAILING_PARENTHETICAL.replace(lower.trimEnd(':', ' ', '-', '–', '—'), "")
            .trimEnd(':', ' ')
        return when (base) {
            in INGREDIENT_HEADERS -> Section.INGREDIENTS
            in INSTRUCTION_HEADERS -> Section.INSTRUCTIONS
            in END_HEADERS -> Section.END
            else -> null
        }
    }

    private val BLOCKQUOTE = Regex("^(?:>\\s*)+")
    private val HEADING = Regex("^#{1,6}(?!\\d)\\s*")
    private val RULE = Regex("^(?:[-*_]\\s*){3,}$")
    private val BULLET = Regex("^[-*+•·▪◦]\\s+")
    private val NUMBERED = Regex("^\\(?\\d{1,2}[.)]\\s+")
    private val STEP_LABEL = Regex("^step\\s*\\d{1,2}\\s*[:.)\\-–—]\\s*", RegexOption.IGNORE_CASE)
    private val CHECKBOX = Regex("^\\[[ xX]?]\\s+")
    private val LINK = Regex("\\[([^\\]]+)]\\((?:[^()]|\\([^)]*\\))*\\)")
    private val EMPHASIS = Regex("\\*\\*|__|~~")
    private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~^|])")

    /**
     * One line of Markdown as plain text: HTML stripped and entities decoded first (Jsoup's
     * `text()`, which also trims and collapses whitespace), then the quote, heading, rule, list
     * marker ("-", "*", "1.", "2)", "Step 3:"), link, emphasis and escape syntax removed. A
     * number followed by a dot and no space ("1.5 cups") is a quantity, not a list marker.
     */
    fun cleanLine(raw: String): String {
        var s = Jsoup.parse(raw).text()
        s = BLOCKQUOTE.replace(s, "")
        s = HEADING.replace(s, "")
        if (RULE.matches(s)) return ""
        s = BULLET.replace(s, "")
        s = CHECKBOX.replace(s, "")
        s = NUMBERED.replace(s, "")
        s = STEP_LABEL.replace(s, "")
        s = LINK.replace(s) { it.groupValues[1] }
        s = EMPHASIS.replace(s, "")
        s = s.trim()
        if (s.length > 2 && (s.first() == '*' || s.first() == '_') && s.last() == s.first()) {
            s = s.substring(1, s.length - 1)
        }
        s = ESCAPE.replace(s) { it.groupValues[1] }
        return s.trim()
    }

    /** "Serves 4" and "Makes 12 cookies" are kept whole; "Servings: 4" and "Yield: 12
     *  cookies" lose their label, as a schema.org `recipeYield` would read. */
    private val YIELD = Regex(
        "^(serves|makes|servings|yields?|portions)\\b\\s*:?\\s*(\\S.*)$",
        RegexOption.IGNORE_CASE
    )
    private val TIME = Regex(
        "^(prep(?:aration)?(?:\\s+time)?|cook(?:ing)?(?:\\s+time)?|bake(?:\\s+time)?|baking\\s+time|total(?:\\s+time)?)\\s*:\\s*(\\S.*)$",
        RegexOption.IGNORE_CASE
    )

    fun split(text: String): SplitRecipe? {
        val ingredients = mutableListOf<String>()
        val instructions = mutableListOf<String>()
        var yield: String? = null
        var prep: String? = null
        var cook: String? = null
        var total: String? = null
        var state: Section? = null

        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = cleanLine(raw)
            if (line.isEmpty()) continue
            val header = section(line)
            if (header != null) {
                state = header
                continue
            }
            when (state) {
                Section.INGREDIENTS -> ingredients.add(line)
                Section.INSTRUCTIONS -> instructions.add(line)
                Section.END -> Unit
                null -> {
                    YIELD.matchEntire(line)?.let { m ->
                        if (yield == null) {
                            val label = m.groupValues[1].lowercase()
                            yield = if (label == "serves" || label == "makes") line else m.groupValues[2].trim()
                        }
                    }
                    TIME.matchEntire(line)?.let { m ->
                        val label = m.groupValues[1].lowercase()
                        val value = JsonLdRecipeParser.formatDuration(m.groupValues[2])
                        when {
                            label.startsWith("prep") -> if (prep == null) prep = value
                            label.startsWith("total") -> if (total == null) total = value
                            else -> if (cook == null) cook = value
                        }
                    }
                }
            }
        }
        if (ingredients.isEmpty() || instructions.isEmpty()) return null
        return SplitRecipe(ingredients, instructions, yield, prep, cook, total)
    }
}
