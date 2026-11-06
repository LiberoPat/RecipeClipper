package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.LanguageWords
import org.json.JSONObject

/**
 * Text shared into the app (#306): a note, an email, a message. Where it goes ([route]) and
 * what the editor opens with ([read]). The rules (#334), in order:
 *
 * 1. **A recipe** ([Route.Editor], sorted): the text splits ([RecipeTextSplitter], in the
 *    language its words say, #208), or is the app's own shared text ([appShared]). It wins over
 *    a link in it: the cook chose this text, and a link beside a recipe may lead anywhere.
 * 2. **A link** ([Route.Link]) only when the text is essentially just the link: at most
 *    [CAPTION_LINES] other lines, each at most [CAPTION_LENGTH] long (a title, a caption: what a
 *    browser or an app shares). Anything more is a recipe for the editor.
 * 3. **A list** ([Route.List]): every non-blank line bulleted, as #149's "Send list" writes
 *    them, headings allowed (short, no full stop, a bulleted line somewhere after: a title, an
 *    aisle). A mix of bulleted lines and prose is a recipe for the editor.
 * 4. **Anything else** ([Route.Editor], unsorted): the editor, every line as written.
 *
 * Nothing shared is lost: the lines a split leaves out ([Reading.leftovers]) go to the review
 * too. Line separators are normalised first ([normalize]). Blank text goes nowhere.
 *
 * Pure: text in, data out. The iOS port (`SharedText.swift`) follows it line for line.
 */
object SharedText {

    /** Where shared text goes. */
    sealed interface Route {
        /** The editor, on [reading]: sorted into a recipe, or every line as written. */
        data class Editor(val reading: Reading) : Route

        /** Today's import of [url]: the text was essentially just the link. */
        data class Link(val url: String) : Route

        /** #149's "Add this list". */
        data object List : Route

        /** Nothing: blank text, or text too long for the editor that is neither a link nor a list. */
        data object None : Route
    }

    /** [route] by name, for the corpus and the tests. */
    enum class Kind { RECIPE, LINK, LIST, TEXT, IGNORED }

    /** Longer text isn't offered to the editor: the review keeps it in saved state. */
    const val MAX_LENGTH = 50_000

    /** A link with at most this many other lines, none longer than [CAPTION_LENGTH], is just a link. */
    const val CAPTION_LINES = 2
    const val CAPTION_LENGTH = 160

    /** The longest unbulleted line a list may hold as a heading ("Groceries", "Produce"). */
    private const val HEADING_LENGTH = 40
    private const val SENTENCE_END = ".!?。！？"

    private val LINK = Regex("https?://\\S+")

    /** Every line break but "\n": "\r\n", "\r", the vertical tab, form feed, NEL, LS and PS
     *  (Pages, Messages on a Mac and Google Docs share some of these, #334). */
    private val SEPARATOR = Regex("\r\n|[\r\u000B\u000C\u0085  ]")

    /** [text] with every line break as "\n". */
    fun normalize(text: String): String = SEPARATOR.replace(text, "\n")

    /** The first http(s) link in [text], as shared (the repository cleans it). */
    fun link(text: String): String? = LINK.find(text)?.value

    fun route(text: String): Route {
        val normalized = normalize(text)
        if (normalized.isBlank()) return Route.None
        val reading = if (normalized.length <= MAX_LENGTH) read(normalized) else null
        if (reading?.sorted == true) return Route.Editor(reading)
        val lines = normalized.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val link = link(normalized)
        if (link != null && justALink(lines)) return Route.Link(link)
        if (isList(lines)) return Route.List
        if (reading != null) return Route.Editor(reading)
        return if (link != null) Route.Link(link) else Route.None
    }

    fun kind(text: String): Kind = when (val route = route(text)) {
        is Route.Editor -> if (route.reading.sorted) Kind.RECIPE else Kind.TEXT
        is Route.Link -> Kind.LINK
        Route.List -> Kind.LIST
        Route.None -> Kind.IGNORED
    }

    /** Rule 2: the links taken out, what's left is at most a short title or caption. */
    private fun justALink(lines: List<String>): Boolean {
        val others = lines.map { LINK.replace(it, "").trim() }.filter { it.isNotEmpty() }
        return others.size <= CAPTION_LINES && others.all { it.length <= CAPTION_LENGTH }
    }

    /** Rule 3: every line bulleted, or a heading with a bulleted line somewhere after it. */
    private fun isList(lines: List<String>): Boolean {
        val lastBullet = lines.indexOfLast(::bulleted)
        if (lastBullet < 0) return false
        return lines.withIndex().all { (i, line) ->
            bulleted(line) || i < lastBullet && line.length <= HEADING_LENGTH &&
                line.last() !in SENTENCE_END && !line.contains("http")
        }
    }

    private fun bulleted(line: String): Boolean =
        line.length >= 2 && line[0] in BULLETS && line[1].isWhitespace() && line.substring(1).isNotBlank()

    /** `ReceivedList`'s bullets. */
    private const val BULLETS = "-*•◦▪·–—"

    /**
     * What the editor opens with: the app's own shared text read back ([appShared]), else the
     * split recipe when the text splits, with the lines the split left out as [Reading.leftovers];
     * else every non-blank line, trimmed and otherwise as written, as the steps. Steps, because
     * a step is never scaled, converted, bought or used up: an unsorted line can't become a
     * confident wrong number. No name either way: the first line might be "Hi Mum," (as #226
     * decided for a scan), so Save points out the rule.
     */
    fun read(text: String): Reading {
        val normalized = normalize(text)
        appShared(normalized)?.let { return it }
        val lines = normalized.split('\n')
        RecipeTextSplitter.detectAndSplitLines(normalized, plain = true)?.let { found ->
            val recipe = found.split.recipe.copy(language = found.language)
            return Reading(recipe, sorted = true, leftovers = found.split.leftover.map { lines[it].trim() })
        }
        val all = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val language = RecipeTextSplitter.languageOf(normalized)
        return Reading(SplitRecipe(emptyList(), all, null, null, null, null, language), sorted = false)
    }

    /**
     * [recipe] is what the editor's fields start with; its language, the text's (#208).
     * [leftovers] are the lines of a sorted text that ended in no field, as written, in order:
     * the story, "Notes:" and what's under it, a link; the review shows them as the note.
     */
    data class Reading(val recipe: SplitRecipe, val sorted: Boolean, val leftovers: List<String> = emptyList())

    /** One UI language's words in the app's own shared text (`splitter.json`'s `shareText`),
     *  compared with a no-break space ("Portions : 4") as a space: an app in between may swap it. */
    private class ShareWords(private val table: JSONObject) {
        private fun word(key: String) = spaced(table.getString(key))
        val ingredients: String = word("ingredients")
        val instructions: String = word("instructions")
        private fun prefix(key: String) = Regex.escape(word(key).substringBefore("{n}"))

        /** "Serves 4", "Makes 3 (originally 6)", "Portionen: 4". */
        val yieldLine = Regex("^(?:${prefix("serves")}|${prefix("makes")})\\d")

        /** One entry of the times line: "Prep 10m"; group 1 prep, 2 cook, 3 total, 4 the time. */
        val timeEntry = Regex(
            "^(?:(${Regex.escape(word("prep"))})|(${Regex.escape(word("cook"))})|(${Regex.escape(word("total"))}))\\s+(\\S.*)$"
        )
    }

    private fun spaced(s: String) = s.replace(' ', ' ')

    private val shareWords: List<ShareWords> by lazy { LanguageWords.SHIPPED.mapNotNull(::shareTable).map(::ShareWords) }

    private fun shareTable(language: String): JSONObject? =
        LanguageWords.forTag(language)?.table("splitter")?.optJSONObject("shareText")

    /** The words the table holds for the app's own shared text in [language]'s UI ("serves" is
     *  "Serves {n}"), for the tests that pin them to each platform's strings; null for a language
     *  the UI doesn't speak. */
    fun shareLabels(language: String): Map<String, String>? = shareTable(language)?.let { table ->
        SHARE_LABEL_KEYS.associateWith { table.getString(it) }
    }

    private val SHARE_LABEL_KEYS = listOf("ingredients", "instructions", "serves", "makes", "prep", "cook", "total")

    /** "1. Mix." as the app numbers a shared step. */
    private val STEP_NUMBER = Regex("^\\d{1,3}\\.\\s+")

    /**
     * Rule 6: the app's own shared text (`RecipeShareText`, #149) read back, whichever of the
     * six UI languages the sender's phone wrote its words in: its two headings on their own
     * lines, in order, with lines under each; above them the serves line and the times line
     * when there were any. Every other line above the headings (the title, a message) is left
     * over. The language is the recipe's own, from its words, not the sender's phone's.
     */
    private fun appShared(text: String): Reading? {
        val lines = text.split('\n').map { it.trim() }
        val compared = lines.map(::spaced)
        for (w in shareWords) {
            val i = compared.indexOf(w.ingredients).takeIf { it >= 0 } ?: continue
            val j = compared.withIndex().indexOfFirst { it.index > i && it.value == w.instructions }.takeIf { it > i } ?: continue
            val ingredients = lines.subList(i + 1, j).filter { it.isNotEmpty() }
            val steps = lines.subList(j + 1, lines.size).filter { it.isNotEmpty() }.map { STEP_NUMBER.replace(it, "") }
            if (ingredients.isEmpty() || steps.isEmpty()) continue
            var yield: String? = null
            var times: List<String?>? = null
            val leftovers = mutableListOf<String>()
            for (line in lines.subList(0, i).filter { it.isNotEmpty() }) {
                val found = if (times == null) timesLine(spaced(line), w) else null
                when {
                    yield == null && w.yieldLine.containsMatchIn(spaced(line)) -> yield = line
                    found != null -> times = found
                    else -> leftovers += line
                }
            }
            val (prep, cook, total) = times ?: listOf(null, null, null)
            val recipe = SplitRecipe(ingredients, steps, yield, prep, cook, total, RecipeTextSplitter.languageOf(text))
            return Reading(recipe, sorted = true, leftovers = leftovers)
        }
        return null
    }

    /** "Prep 10m · Cook 20m · Total 30m" as [prep, cook, total]; null unless every entry is one. */
    private fun timesLine(line: String, w: ShareWords): List<String?>? {
        val found = arrayOfNulls<String>(3)
        for (entry in line.split(" · ")) {
            val m = w.timeEntry.matchEntire(entry.trim()) ?: return null
            val slot = (1..3).first { m.groupValues[it].isNotEmpty() } - 1
            if (found[slot] != null) return null
            found[slot] = m.groupValues[4]
        }
        return found.toList()
    }
}
