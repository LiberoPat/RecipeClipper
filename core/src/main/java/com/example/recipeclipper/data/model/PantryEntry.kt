package com.example.recipeclipper.data.model

/**
 * What was typed into the Pantry's "Add to the pantry" field (#302): the item's [name], and its
 * [quantity] when the text started with one, as typed ("3", "2 cups", "1½ lb").
 */
data class PantryEntry(val name: String, val quantity: String?) {

    companion object {

        /**
         * [text], trimmed, split into an amount and a name only when it starts with one real
         * amount that [words] read: a positive number or fraction, then a unit the tables know or
         * a space, then a name that starts with a letter ("3 onions", "2 cups rice", "500 g
         * Mehl"). A container word belongs to the amount as a unit does ("2 cans tomatoes",
         * amounts.json `containers`). A word joining the unit or container to the name ("2 cups
         * of rice", "200 g de farine", amounts.json `unitLinks`) belongs to neither. The name is
         * otherwise the rest as typed.
         *
         * Anything else stays whole as the name, so no number is guessed: "00 flour", "7up",
         * "5-spice", "five-spice powder", a number alone ("1/2"), a range ("2-3 onions"), two
         * amounts ("2 eggs plus 3 yolks", "1 cup plus 2 tbsp flour", "2 cups rice (400 g)", a
         * number left in the name), a size ("2-inch ginger"), a temperature ("180 C"), and every
         * text in a language with no tables, or whose amounts follow the name (Japanese).
         */
        fun split(text: String, words: LanguageWords?): PantryEntry {
            val typed = text.trim()
            val whole = PantryEntry(typed, null)
            if (words == null) return whole
            val p = IngredientScaler.patterns(words)
            if (p.amountAfterName || p.unreadable(typed)) return whole
            // "2 eggs plus 3 yolks", "1 cup butter or 1/2 cup oil": more than one amount.
            if (IngredientScaler.sides(p, typed)?.size != 1) return whole
            val lead = p.leading.find(typed) ?: return whole
            if (lead.groupValues[4].isNotEmpty()) return whole // a range is no one figure
            if ((p.parse(lead.groupValues[2]) ?: 0.0) <= 0.0) return whole // "00 flour"
            val afterNumber = lead.range.last + 1
            val rest = typed.substring(afterNumber)
            if (p.notAnAmount.containsMatchIn(rest) || p.temperature(lead, rest)) return whole
            val c = UnitConverter.patterns(words)
            var end = afterNumber
            var nameStart = afterNumber
            val unit = p.unitAtStart.find(rest)
            if (unit != null) {
                if (MeasureUnit.fromText(unit.groupValues[1], words) == null) return whole
                end += unit.range.last + 1
                val after = typed.substring(end)
                // "1 cup plus 2 tbsp flour": a second part.
                if (c.continuationAtStart.containsMatchIn(after)) return whole
                nameStart = end + (links(words).find(after)?.let { it.range.last + 1 } ?: 0)
            } else if (rest.firstOrNull()?.isWhitespace() != true) {
                return whole // "7up", "5-spice"
            } else {
                // "2 cans tomatoes", "1 Dose Tomaten": a container (amounts.json `containers`)
                // belongs to the amount, as a unit does, so the name is just "tomatoes".
                p.containerFirst.find(rest)?.let { container ->
                    end = afterNumber + container.range.last + 1
                    nameStart = end + (links(words).find(typed.substring(end))?.let { it.range.last + 1 } ?: 0)
                }
            }
            val name = typed.substring(nameStart).trim()
            if (name.firstOrNull()?.isLetter() != true || name.any { it in DIGITS || it in FRACTIONS }) return whole
            if (!GroceryCombiner.notesOnly(name, c)) return whole
            return PantryEntry(name, typed.substring(0, end).trim())
        }

        private const val DIGITS = "0123456789０１２３４５６７８９"
        private const val FRACTIONS = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

        private class Links(words: LanguageWords) {
            val atStart = Regex(
                """^\s*${SharedTables.alternation(words.strings("amounts", "unitLinks"))}""",
                RegexOption.IGNORE_CASE
            )
        }

        private fun links(words: LanguageWords): Regex = words.compiled(Links::class) { Links(it) }.atStart
    }
}
