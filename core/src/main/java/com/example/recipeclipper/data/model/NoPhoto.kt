package com.example.recipeclipper.data.model

/**
 * What a recipe's image is drawn from when it has no photo (#403): its stable [uid] (the tint),
 * its [title] (the monogram), its stored [language] and its ingredient [ingredients] lines (the
 * label). Never a stock image: only the recipe's own words.
 */
data class NoPhotoSource(
    val uid: String,
    val title: String,
    val language: String? = null,
    val ingredients: List<String> = emptyList()
)

/**
 * One photo on a list's cover (#286): its [url], and [source], what the recipe's designed image
 * is drawn from when the photo fails to load (#403).
 */
data class CoverPhoto(val url: String, val source: NoPhotoSource)

/** What [NoPhoto.art] picked: the monogram's letter, the label's names, and the tint. */
data class NoPhotoArt(val initial: String?, val labels: List<String>, val tint: Int)

/**
 * The words and colour of a recipe's designed image when it has no photo (#403): the title's
 * first letter, skipping a leading article from `shared/tables/<language>/articles.json`; the
 * first ingredient names as written, without amount or unit (read by [IngredientName]),
 * skipping headings and the language's staples (`staples.json`: salt, pepper, oil, water);
 * and one of [TINTS] warm tints from a stable hash of the recipe's uid, so a recipe keeps its
 * colour everywhere and after a rename. Pure, and written twice (iOS's `NoPhoto`): the hash is
 * FNV-1a, the same on both, so a recipe has one colour on both platforms.
 */
object NoPhoto {

    /** Paprika, saffron, sage, cocoa and rose. */
    const val TINTS = 5

    private class Articles(words: LanguageWords) {
        // Longest first, so "un'" is tried before "un"; each as a list of code points.
        val spaced: List<List<Int>>
        val glued: List<List<Int>>

        init {
            val all = words.strings("articles", "articles").map { it.lowercase().codePoints().toArray().toList() }
                .sortedByDescending { it.size }
            glued = all.filter { it.last() == '\''.code || it.last() == '’'.code }
            spaced = all - glued.toSet()
        }
    }

    private class Staples(words: LanguageWords) {
        val keys: Set<String> = words.strings("staples", "staples")
            .map { IngredientName.key(IngredientDensities.headPhrase(it, words), words) }.toSet()
    }

    /**
     * The title's first letter, uppercased, after a leading article of the recipe's language;
     * null when the title has no letter at all. An article that would leave no letter stays
     * ("The" is T). The title's words only: nothing is translated.
     */
    fun initial(title: String, language: String?): String? {
        val words = LanguageWords.forRecipe(language, title, emptyList())
        var points = title.trim().codePoints().toArray().toList()
        if (words != null) {
            val articles = words.compiled(Articles::class) { Articles(it) }
            val rest = withoutArticle(points, articles)
            if (rest.any { isLetter(it) }) points = rest
        }
        val first = points.firstOrNull { isLetter(it) } ?: return null
        val letter = String(Character.toChars(first))
        val upper = letter.uppercase()
        return if (upper.codePointCount(0, upper.length) == 1) upper else letter
    }

    private fun withoutArticle(points: List<Int>, articles: Articles): List<Int> {
        for (article in articles.glued) {
            if (points.size > article.size && lower(points.take(article.size)) == article) return points.drop(article.size)
        }
        for (article in articles.spaced) {
            if (points.size > article.size && lower(points.take(article.size)) == article &&
                Character.isWhitespace(points[article.size])
            ) {
                return points.drop(article.size).dropWhile { Character.isWhitespace(it) }
            }
        }
        return points
    }

    private fun lower(points: List<Int>): List<Int> =
        String(points.toIntArray(), 0, points.size).lowercase().codePoints().toArray().toList()

    private fun isLetter(codePoint: Int): Boolean = Character.isLetter(codePoint)

    /**
     * Up to [max] ingredient names, in the recipe's order: each line's name as written (its own
     * capitals), without amount, unit or preparation. A heading, a line [IngredientName] can't
     * name ("salt and pepper", "juice of 1 lemon"), a staple and a name already taken are
     * skipped. Empty when none is left (or [language] has no words): the caller shows the
     * monogram.
     */
    fun labels(ingredients: List<String>, language: String?, max: Int): List<String> {
        if (max <= 0) return emptyList()
        val words = LanguageWords.forRecipe(language, "", ingredients) ?: return emptyList()
        val staples = words.compiled(Staples::class) { Staples(it) }.keys
        val taken = mutableSetOf<String>()
        val labels = mutableListOf<String>()
        for (line in ingredients) {
            if (IngredientHeading.isHeading(line)) continue
            val name = IngredientName.of(line, words) ?: continue
            val key = IngredientName.key(name, words)
            if (key in staples || !taken.add(key)) continue
            labels.add(written(line, name))
            if (labels.size == max) break
        }
        return labels
    }

    // The name as the line writes it ("Zucker", "d'agneau", "all-purpose flour"): the name's
    // letters in the line, case aside, with an apostrophe the name dropped and a hyphen or a run
    // of spaces where it has a space. The name as read (lowercase) when the line doesn't hold it.
    private fun written(line: String, name: String): String {
        val text = line.codePoints().toArray()
        val wanted = name.codePoints().toArray()
        for (start in text.indices) {
            val end = matchAt(text, start, wanted) ?: continue
            return String(text, start, end - start)
        }
        return name
    }

    private fun matchAt(text: IntArray, start: Int, name: IntArray): Int? {
        var at = start
        var j = 0
        while (j < name.size) {
            val c = name[j]
            if (c == ' '.code) {
                if (at >= text.size || !isSeparator(text[at])) return null
                while (at < text.size && isSeparator(text[at])) at++
                j++
            } else if (at < text.size && Character.toLowerCase(text[at]) == Character.toLowerCase(c)) {
                at++
                j++
            } else if (at > start && at < text.size && (text[at] == '\''.code || text[at] == '’'.code)) {
                at++
            } else {
                return null
            }
        }
        return at
    }

    private fun isSeparator(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || codePoint == '-'.code

    /** One of [TINTS] from [uid]: FNV-1a over its UTF-8 bytes, modulo five. Never changes. */
    fun tintIndex(uid: String): Int {
        var hash = 2166136261L
        for (byte in uid.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return (hash % TINTS).toInt()
    }

    /** Everything the image draws: the monogram's letter, up to [maxLabels] names, the tint. */
    fun art(source: NoPhotoSource, maxLabels: Int): NoPhotoArt = NoPhotoArt(
        initial = initial(source.title, source.language),
        labels = labels(source.ingredients, source.language, maxLabels),
        tint = tintIndex(source.uid)
    )
}
