package com.example.recipeclipper.data.model

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One language's parsing words (#14): the tables under `shared/tables/<language>/`, which iOS
 * loads too. The recipe's language picks the table, never the phone's, and languages are never
 * merged: "C" is a cup in English and Celsius elsewhere, and mixed rules convert wrongly.
 *
 * Every parser takes one (English by default, so existing callers and the differential corpus
 * are unchanged). A null table means a language the app has no words for: every line stays as
 * written, with no scaling, conversion, temperature rewrite, timer or servings stepper, since
 * English rules would read "2 bis 3" as "4 bis 3".
 *
 * Adding a language is adding its folder of tables and its code to [SHIPPED]. A language in
 * [DETECTED] only (a folder holding just `language.json`) can be recognised but not read, so
 * its recipes stay as written.
 */
class LanguageWords private constructor(
    /** The primary language subtag, e.g. "en". */
    val language: String
) {
    internal fun table(name: String): JSONObject = SharedTables.load(name, language)

    internal fun strings(table: String, key: String): List<String> =
        SharedTables.strings(table(table).getJSONArray(key))

    /** Words that join the ends of a range ("4 to 6"), as one alternation. */
    internal val rangeWords: String = SharedTables.alternation(strings("ranges", "words"))

    private val compiled = ConcurrentHashMap<Any, Any>()

    /**
     * The patterns [owner] builds from these words, built once per language. Each parser keeps
     * its own, so its regexes stay beside its logic.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> compiled(owner: Any, build: (LanguageWords) -> T): T =
        compiled.getOrPut(owner) { build(this) } as T

    override fun toString(): String = "LanguageWords($language)"

    companion object {
        /** Languages with every table: their recipes are read with their own words. */
        val SHIPPED: List<String> = listOf("en")

        /**
         * Languages detection can recognise: every shipped one, plus some with only
         * `language.json` so far, so a German page that declares `en` is recognised as German
         * (and shown as written) rather than read with English rules.
         */
        val DETECTED: List<String> = listOf("en", "de", "es", "fr", "it", "pt")

        private val loaded: Map<String, LanguageWords> by lazy { SHIPPED.associateWith { LanguageWords(it) } }

        private val detectors: Map<String, Regex> by lazy {
            DETECTED.associateWith { language ->
                val words = SharedTables.strings(SharedTables.load("language", language).getJSONArray("detect"))
                Regex("""(?<!\p{L})${SharedTables.alternation(words)}(?!\p{L})""", RegexOption.IGNORE_CASE)
            }
        }

        val ENGLISH: LanguageWords get() = loaded.getValue("en")

        private val TAG = Regex("""[a-z]{2,3}(?:-[a-z0-9]{1,8})*""")

        /**
         * A language tag as stored: trimmed, lowercased, `_` as `-` ("en_US" is "en-us"). Null
         * for anything that isn't a tag ("English", "", "x").
         */
        fun normalize(tag: String?): String? =
            tag?.trim()?.lowercase()?.replace('_', '-')?.takeIf { TAG.matches(it) }

        /** The words for a stored tag, by its primary subtag; null for a language with none. */
        fun forTag(tag: String?): LanguageWords? =
            normalize(tag)?.substringBefore('-')?.let { loaded[it] }

        /**
         * The detected language whose words the text uses most, when clearly ahead: at least 3
         * hits and more than twice the runner-up's. Null when nothing is clear.
         */
        fun detect(text: String): String? {
            val scores = DETECTED.map { it to detectors.getValue(it).findAll(text).count() }
                .sortedByDescending { it.second }
            val (best, score) = scores.first()
            val runnerUp = scores.getOrNull(1)?.second ?: 0
            return best.takeIf { score >= 3 && score > 2 * runnerUp }
        }

        /**
         * The recipe's language: the JSON-LD `inLanguage`, else the page's `<html lang>`, else
         * English, unless the recipe's own words clearly say another language ([detect]), which
         * then wins (the owner's decision on #14: pages mislabel themselves, and
         * mulherportuguesa.com declares `en` on Portuguese recipes). Ambiguous words keep the
         * declared language. Never the phone's locale: a Spanish speaker may share an English
         * recipe.
         */
        fun resolve(declared: String?, page: String?, text: () -> String): String {
            val stated = normalize(declared) ?: normalize(page)
            val detected = detect(text())
            return when {
                stated == null -> detected ?: "en"
                detected != null && detected != stated.substringBefore('-') -> detected
                else -> stated
            }
        }

        /** The text detection reads: the name and the ingredient lines. */
        fun detectionText(name: String, ingredients: List<String>): String =
            (listOf(name) + ingredients).joinToString("\n")

        /** The words a recipe is read with. A recipe stored before #14 has no language: detect it. */
        fun forRecipe(recipe: Recipe): LanguageWords? =
            forTag(recipe.language ?: resolve(null, null) { detectionText(recipe.name, recipe.ingredients) })
    }
}
