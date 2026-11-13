package com.example.recipeclipper.data.model

/**
 * A recipe as printed (#318): the card the print dialog or a cookbook PDF draws, as an ordered
 * list of [Block]s. Pure, like [RecipeShareText], and built from the same inputs: the lines
 * already rendered for the screen (scaled, converted), so nothing here scales, converts or
 * re-parses. Ticked lines print plain. The platforms only measure and draw the blocks; where
 * the pages break is [paginate], and a cookbook's page numbers are [startPages], so both apps
 * break a recipe at the same block, or the same line of one taller than a page. iOS's
 * `RecipePrint` is a line-for-line port.
 */
object RecipePrint {

    /** One thing on the page, top to bottom. */
    sealed class Block {
        /** The recipe's photo, when it is cached on the phone; the platform loads [url]. */
        data class Photo(val url: String) : Block()
        data class Title(val text: String) : Block()

        /** The site it came from ("smittenkitchen.com"), quietly under the title. */
        data class Credit(val text: String) : Block()

        /** The serves line, then the times line ([RecipeShareText.metaLines]). */
        data class Meta(val text: String) : Block()
        data class Section(val text: String) : Block()

        /** An ingredient group heading ([IngredientHeading]): no bullet, never scaled. */
        data class GroupHeading(val text: String) : Block()
        data class Ingredient(val text: String) : Block()
        data class Step(val number: Int, val text: String) : Block()
        data class Note(val text: String) : Block()
    }

    /** The section headings, from the screen's strings; [share] gives the serves and times words. */
    data class Labels(
        val share: RecipeShareText.Labels,
        val ingredients: String,
        val instructions: String,
        val notes: String
    ) {
        companion object {
            val ENGLISH = Labels(RecipeShareText.Labels.ENGLISH, "Ingredients", "Instructions", "Notes")
        }
    }

    /**
     * [recipe]'s card. [ingredients] and [instructions] are the lines as shown; [note] is the
     * cook's own (printed on a single card; a cookbook leaves it out, as Send as file does);
     * [photo] says whether to place the photo, which the platform draws only if it is cached.
     */
    fun blocks(
        recipe: Recipe,
        servings: ServingsScale?,
        ingredients: List<String>,
        instructions: List<String>,
        sourceDomain: String?,
        note: String?,
        labels: Labels = Labels.ENGLISH,
        photo: Boolean = true
    ): List<Block> = buildList {
        recipe.image?.takeIf { photo && it.isNotBlank() }?.let { add(Block.Photo(it)) }
        add(Block.Title(recipe.name))
        sourceDomain?.takeIf { it.isNotBlank() }?.let { add(Block.Credit(it)) }
        RecipeShareText.metaLines(recipe, servings, labels.share).forEach { add(Block.Meta(it)) }
        val lines = ingredients.filter { it.isNotBlank() }
        if (lines.isNotEmpty()) {
            add(Block.Section(labels.ingredients))
            lines.forEach { line ->
                add(if (IngredientHeading.isHeading(line)) Block.GroupHeading(line.trim()) else Block.Ingredient(line))
            }
        }
        val steps = instructions.filter { it.isNotBlank() }
        if (steps.isNotEmpty()) {
            add(Block.Section(labels.instructions))
            steps.forEachIndexed { index, step -> add(Block.Step(index + 1, step)) }
        }
        note?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add(Block.Section(labels.notes))
            add(Block.Note(it))
        }
    }

    /**
     * Whether [block] must not end a page: the title and what sits under it, and headings, stay
     * with the first line after them.
     */
    fun keepsWithNext(block: Block): Boolean = when (block) {
        is Block.Photo, is Block.Title, is Block.Credit, is Block.Meta, is Block.Section, is Block.GroupHeading -> true
        else -> false
    }

    /**
     * A block measured for paper: the [space] above it (dropped at the top of a page) and the
     * heights of its [lines], top to bottom (a photo is one line); [keep] is [keepsWithNext].
     */
    data class Measured(val space: Double, val lines: List<Double>, val keep: Boolean) {
        val height: Double get() = space + lines.sum()
    }

    /** Lines [first] until [end] of block [block] (both indexes), on [page] (from 0). */
    data class Piece(val block: Int, val page: Int, val first: Int, val end: Int)

    /**
     * Where [blocks] go on pages [pageHeight] tall, in order: each block as one [Piece], or, a
     * block taller than a page, as one piece per page it runs over, split between lines, so
     * nothing is drawn off a page. A block that doesn't fit starts a new page, and so does a run
     * of kept blocks with the line after it, unless that run is taller than a page by itself;
     * a block taller than a page needs only its first line to start on a page.
     */
    fun paginate(blocks: List<Measured>, pageHeight: Double): List<Piece> {
        val pieces = ArrayList<Piece>(blocks.size)
        var page = 0
        var used = 0.0
        fun start(i: Int) = blocks[i].let { if (it.height <= pageHeight) it.height else it.space + (it.lines.firstOrNull() ?: 0.0) }
        for (i in blocks.indices) {
            var run = start(i)
            var j = i
            while (j < blocks.size - 1 && blocks[j].keep) {
                j++
                run += start(j)
            }
            val needed = if (run <= pageHeight) run else start(i)
            if (used > 0 && used + needed > pageHeight) {
                page++
                used = 0.0
            }
            val block = blocks[i]
            if (block.height <= pageHeight) {
                pieces += Piece(i, page, 0, block.lines.size)
                used += block.height
                continue
            }
            var first = 0
            block.lines.forEachIndexed { l, line ->
                val height = line + if (l == 0) block.space else 0.0
                if (used > 0 && used + height > pageHeight) {
                    if (l > first) pieces += Piece(i, page, first, l)
                    page++
                    used = 0.0
                    first = l
                }
                used += height
            }
            pieces += Piece(i, page, first, block.lines.size)
        }
        return pieces
    }

    /**
     * A cookbook's page numbers (from 1): the title page is 1, then [contentsPages] pages of
     * contents, then each recipe from a new page, taking [pageCounts] pages each in order.
     * Returns the first page of each recipe, for the contents.
     */
    fun startPages(pageCounts: List<Int>, contentsPages: Int): List<Int> {
        var next = 1 + contentsPages + 1
        return pageCounts.map { count -> next.also { next += maxOf(count, 1) } }
    }
}
