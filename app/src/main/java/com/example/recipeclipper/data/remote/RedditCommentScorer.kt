package com.example.recipeclipper.data.remote

/** One comment on a Reddit post: its Markdown, and whether the post's author wrote it
 *  (Reddit's `is_submitter`). */
data class RedditComment(val body: String, val bySubmitter: Boolean = false)

/**
 * Picks the comment on a Reddit post most likely to be the recipe: the recipe the poster added
 * as a comment (r/recipes asks for exactly that when the post is a photo), else a transcription
 * of a photographed recipe card.
 *
 * [score] is a pure function over one comment's text. It adds up three signals:
 *  - an ingredients header line (+3) and an instructions header line (+3), recognised exactly
 *    as [RecipeTextSplitter] recognises them;
 *  - an explicit mention of transcribing ("Transcription:", "I transcribed it") (+2);
 *  - a plausible length: 4 to 150 non-empty lines (+1); fewer than 4 (-2), since a recipe
 *    doesn't fit in a remark; more than 150 (-1).
 * Never below 0. "[deleted]" and "[removed]" score 0.
 *
 * [pick] puts the poster's own comments first, then ranks by score, ties going to the earlier
 * comment (Reddit returns them in "best" order), and returns the first that
 * [RecipeTextSplitter] can actually split. A comment that scores well but has no clear
 * structure is passed over, never guessed at.
 *
 * Pure: text in, text out. The iOS port (`RedditCommentScorer.swift`) is the same rules.
 */
object RedditCommentScorer {

    private val TRANSCRIPTION = Regex(
        "\\btranscri(?:be|bed|bing|ption|ptions|pt)\\b",
        RegexOption.IGNORE_CASE
    )

    private val GONE = setOf("[deleted]", "[removed]")

    const val MIN_LINES = 4
    const val MAX_LINES = 150

    fun score(text: String): Int {
        if (text.trim() in GONE) return 0
        val lines = RecipeTextSplitter.lines(text).filter { it.text.isNotEmpty() }
        if (lines.isEmpty()) return 0
        val sections = lines.mapNotNull { RecipeTextSplitter.header(it)?.section }.toSet()

        var score = 0
        if (RecipeTextSplitter.Section.INGREDIENTS in sections) score += 3
        if (RecipeTextSplitter.Section.INSTRUCTIONS in sections) score += 3
        if (TRANSCRIPTION.containsMatchIn(text)) score += 2
        score += when {
            lines.size < MIN_LINES -> -2
            lines.size <= MAX_LINES -> 1
            else -> -1
        }
        return maxOf(0, score)
    }

    /** The best comment that splits into a recipe, the poster's first; null when none does. */
    fun pick(comments: List<RedditComment>): String? =
        comments.withIndex()
            .map { it to score(it.value.body) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<IndexedValue<RedditComment>, Int>> { it.first.value.bySubmitter }
                    .thenByDescending { it.second }
                    .thenBy { it.first.index }
            )
            .firstOrNull { RecipeTextSplitter.split(it.first.value.body) != null }
            ?.first?.value?.body
}
