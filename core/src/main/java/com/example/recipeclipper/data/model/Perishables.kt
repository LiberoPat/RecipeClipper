package com.example.recipeclipper.data.model

/**
 * A group of fresh ingredients that go off within days (#396): [key] is the same in every
 * language's table, [minDays] and [maxDays] the "keeps about N days once bought" hint's range.
 * The numbers are a hint, never a date the app claims to know.
 */
data class PerishableGroup(val key: String, val minDays: Int, val maxDays: Int) {
    /** The range as the screen says it: "1-2", or "3" for a group whose two ends are equal. */
    val keeps: String get() = if (minDays == maxDays) "$minDays" else "$minDays-$maxDays"
}

/** An ingredient that matched a [PerishableGroup]; [name] is the ingredient's name, as [IngredientName.of] reads it. */
data class Perishable(val group: PerishableGroup, val name: String)

/**
 * The perishables table (#396, `shared/tables/<language>/perishables.json`): which ingredients
 * are fresh food that keeps only days once bought. Matches by the **end** of the ingredient's
 * name, longest alias first, exactly as [IngredientDensities] does ("smoked salmon" is a `skip`
 * entry, which beats "salmon" by being longer; "fish sauce" never ends in "fish"). A listed
 * singular and plural are one word ([IngredientName.key]). A name the table doesn't list, or a
 * line the app can't name, is not perishable as far as the app says, so it is bought now: the
 * failure is the cook buying early, never an unsure claim about the food.
 */
object Perishables {

    /**
     * A meal at least this many days from today is far enough away for its perishable
     * ingredients to wait for a second shop (Shop for this week, #396). Four is at or past the
     * low end (`minDays`) of every group's range (the table's highest is mushrooms' 4, which
     * `PerishablesTest` pins), so a meal four or more days out is one a shop made today may
     * already have outlasted; a nearer one is bought now, with everything else. A fixed number,
     * never a date, and a hint like the table's own.
     */
    const val LATER_DAYS = 4

    private class Table(words: LanguageWords) {
        val groups: Map<String, PerishableGroup>

        // Openings that make a name a product made from the food, not the food ("caldo de", "confiture de").
        val skipPrefixes: List<String>

        // Longest alias first; a skip entry has a null group. Ties keep the file's order, skips first.
        val aliases: List<Pair<String, PerishableGroup?>>

        init {
            val table = words.table("perishables")
            val found = LinkedHashMap<String, PerishableGroup>()
            val entries = ArrayList<Pair<String, PerishableGroup?>>()
            entries += SharedTables.strings(table.getJSONArray("skip")).map { it to null }
            for (g in SharedTables.objects(table.getJSONArray("groups"))) {
                val group = PerishableGroup(g.getString("key"), g.getInt("minDays"), g.getInt("maxDays"))
                found[group.key] = group
                entries += SharedTables.strings(g.getJSONArray("aliases")).map { it to group }
            }
            groups = found
            skipPrefixes = SharedTables.strings(table.optJSONArray("skipPrefixes")).map { IngredientName.key(it, words) }
            aliases = entries
                .map { (alias, group) -> IngredientName.key(alias, words) to group }
                .sortedByDescending { it.first.length }
        }
    }

    private fun table(words: LanguageWords): Table = words.compiled(Table::class) { Table(it) }

    /** Every group of [words]'s table, in file order. */
    fun groups(words: LanguageWords = LanguageWords.ENGLISH): List<PerishableGroup> = table(words).groups.values.toList()

    /**
     * The group an ingredient [name] ([IngredientName.of]'s answer, or a typed one) belongs to, or
     * null when it matches none, or matches a skip entry. Null [words] (a language the app has no
     * words for) matches nothing.
     */
    fun groupOf(name: String, words: LanguageWords?): PerishableGroup? {
        if (words == null) return null
        val table = table(words)
        val phrase = IngredientName.key(IngredientDensities.headPhrase(name, words), words)
        if (phrase.isEmpty()) return null
        if (table.skipPrefixes.any { phrase.startsWith("$it ") }) return null
        return table.aliases.firstOrNull { (alias, _) -> IngredientDensities.endsWithName(phrase, alias, words.spaced) }?.second
    }

    /** The perishable an ingredient [line] is, by the name [IngredientName.of] reads from it; null for none. */
    fun find(line: String, words: LanguageWords?): Perishable? {
        if (words == null) return null
        val name = IngredientName.of(line, words) ?: return null
        return groupOf(name, words)?.let { Perishable(it, name) }
    }

    /**
     * The most delicate perishable among [lines]: the shortest-keeping group (by
     * [PerishableGroup.maxDays], then [PerishableGroup.minDays]); the first line wins a tie.
     * Null when none is perishable.
     */
    fun mostDelicate(lines: List<String>, words: LanguageWords?): Perishable? =
        lines.mapNotNull { find(it, words) }
            .minWithOrNull(compareBy({ it.group.maxDays }, { it.group.minDays }))
}
