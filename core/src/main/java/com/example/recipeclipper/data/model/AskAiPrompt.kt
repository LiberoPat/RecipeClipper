package com.example.recipeclipper.data.model

import java.util.Locale

/**
 * The prompts "Ask an AI" copies or shares (#351, #353). No AI runs in the app: the cook pastes the
 * prompt into any chat and shares the answer back in (#350). Every prompt asks for schema.org Recipe
 * JSON in a ```json block, with the servings, the units and the oven temperature the cook chose in
 * Settings, in the UI language, so the replies come back in one shape.
 *
 * Pure. The words come in as [Words], which the screen reads from its string resources (the way
 * [RecipeShareText] takes its [RecipeShareText.Labels]); [Words.ENGLISH] is the default for tests.
 * The cook's own text (a request, a pantry item, a title) goes in as typed. The lists are capped
 * ([MAX_PANTRY], [MAX_TITLES]) so one prompt stays a sensible size.
 *
 * The meal-plan format ("Plan my week", read by #352) is a JSON **array of schema.org Recipe
 * objects**, one per listed day, each with one extra property, [DAY_FIELD] (`"day"`), holding that
 * day's date as `YYYY-MM-DD`, exactly as the prompt listed it. A reader that finds the Recipes in
 * the array with `JsonLdRecipeParser` takes each one's day from that property.
 *
 * Plan my week with choices (#392, #367, [planMyWeekPlus], behind the `planMyWeekPlus` flag) asks for
 * one recipe for each chosen meal on each day, each object carrying `"day"` and `"meal"` (the
 * meal's name exactly as listed). From the cook's own recipes it lists up to [MAX_CANDIDATES]
 * of them, most recently viewed first, as `name | link` ([PlanCandidate]: the link is the
 * recipe's `sourceUrl`, as `isBasedOn` in "Change this recipe") and asks the AI to name a saved
 * recipe by that link instead of writing it out; the reader (`MealPlanImport`) finds it again.
 *
 * "Change this recipe" sends the recipe as schema.org JSON with `isBasedOn` set to the recipe's
 * `sourceUrl`: the one id every recipe has, unique and never changing (the cleaned link of a
 * clipped page, `manual:<uuid>` for a typed-in one), and a URL, which is what schema.org's
 * `isBasedOn` takes. #354 finds the original by it.
 */
object AskAiPrompt {

    /** The meal plan's extra property: the day's ISO date. */
    const val DAY_FIELD = "day"

    /** At most this many pantry items go into a prompt, the ones to use up first always. */
    const val MAX_PANTRY = 60

    /** At most this many favourites, and this many recently cooked recipes, go into "Plan my week". */
    const val MAX_TITLES = 15

    /** The days "Plan my week" asks for: a week from the start day. */
    const val PLAN_DAYS = PlanDays.BLOCK_DAYS

    /** The meal plan's second extra property (#392): the meal's name, as the prompt listed it. */
    const val MEAL_FIELD = "meal"

    /** At most this many of the cook's recipes are named in a plan prompt (#367), most recently viewed first. */
    const val MAX_CANDIDATES = 40

    /** More than this many recipes to write out make an answer chat apps may cut off (#392): the sheet warns. */
    const val LONG_ANSWER = 14

    /** Where a week's recipes come from (#367). */
    enum class PlanSource { NEW, MINE, MIX }

    /** A saved recipe offered to the AI: its [name] and its [link] (the recipe's `sourceUrl`). */
    data class PlanCandidate(val name: String, val link: String)

    /**
     * The recipes of [summaries] as candidates to name (#367), most recently viewed first (the
     * newest id first among equals): the tour's sample and any without a name or link are left out.
     * The prompt names the first [MAX_CANDIDATES]; the sheet counts them all.
     */
    fun candidates(summaries: List<RecipeSummary>): List<PlanCandidate> =
        summaries
            .filter { it.title.isNotBlank() && it.sourceUrl.isNotBlank() && !SampleRecipe.isSample(it.sourceUrl) }
            .sortedWith(compareByDescending<RecipeSummary> { it.lastViewedAt }.thenByDescending { it.id })
            .map { PlanCandidate(it.title, it.sourceUrl) }

    /**
     * What the plan sheet chose (#392, #367): the [days] (epoch days, from the start day), the
     * [meals] (the meal types' names, in the cook's order, as listed in the prompt), where the
     * recipes come from, the [candidates] to name (most recently viewed first) and, for a mix, how
     * many of the cook's recipes at most ([mix]).
     */
    data class PlanChoice(
        val days: List<Long>,
        val meals: List<String>,
        val source: PlanSource = PlanSource.NEW,
        val candidates: List<PlanCandidate> = emptyList(),
        val mix: Int = 0
    ) {
        /** The meals asked for, trimmed, blanks and repeats left out. */
        val mealNames: List<String>
            get() = meals.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }

        /** The recipes asked for: every meal on every day. */
        val slots: Int get() = days.size * mealNames.size

        /** The candidates actually named: usable ones, at most [MAX_CANDIDATES]. */
        val named: List<PlanCandidate>
            get() = candidates.map { PlanCandidate(it.name.trim(), it.link.trim()) }
                .filter { it.name.isNotEmpty() && it.link.isNotEmpty() }
                .take(MAX_CANDIDATES)

        /** The recipes the AI has to write out, which is what makes an answer long. */
        val writtenOut: Int get() = recipesToWrite(source, slots, mix)

        /** The answer may be long enough for a chat app to cut it off. */
        val longAnswer: Boolean get() = writtenOut > LONG_ANSWER
    }

    /**
     * How many of a week's [slots] meals the AI writes out in full: all of them for new recipes,
     * none from the cook's own (named by link), and for a [mix] the rest beyond the cook's.
     */
    fun recipesToWrite(source: PlanSource, slots: Int, mix: Int): Int = when (source) {
        PlanSource.NEW -> slots
        PlanSource.MINE -> 0
        PlanSource.MIX -> (slots - mix.coerceIn(1, slots.coerceAtLeast(1))).coerceAtLeast(0)
    }

    /**
     * The words every prompt is made of, in the UI language. [language] is that language's own
     * name ("Deutsch"), for "write it in …". [weekday] names 1 = Sunday … 7 = Saturday
     * ([PlanDays.dayOfWeek]). The `reply…` lines are the sentence that asks for the JSON block.
     */
    data class Words(
        val language: String,
        val weekday: (Int) -> String,
        val wantToCook: (request: String) -> String,
        val changeThis: (request: String) -> String,
        val recipeBelow: String,
        val whatCanIMake: String,
        val useUpFirst: (items: String) -> String,
        val inPantry: (items: String) -> String,
        val planIntro: String,
        val favourites: (titles: String) -> String,
        val cookedLately: (titles: String) -> String,
        val alsoWish: (request: String) -> String,
        val replyOne: String,
        val replyPlan: String,
        val replyChanged: String,
        val recipeFields: String,
        val unitsAsWritten: String,
        val unitsMetric: String,
        val unitsOunces: String,
        val temperatureCelsius: String,
        val temperatureFahrenheit: String,
        val writeIn: (language: String) -> String,
        val noteOutside: String,
        /** "Plan one recipe for each of these meals (Breakfast, Dinner) on each of these days:" (#392). */
        val planMealsIntro: (meals: String) -> String,
        /** Heads the saved recipes, one `name | link` per line (#367). */
        val savedRecipesIntro: String,
        /** From my recipes only: name them by link, don't write them out. */
        val useSavedOnly: String,
        /** A mix: up to this many saved ones by link, new ones written out. */
        val useSavedMix: (count: Int) -> String,
        /** The reply format with a `"day"` and a `"meal"` on each object (#392). */
        val replyPlanMeals: String
    ) {
        companion object {
            val ENGLISH = Words(
                language = "English",
                weekday = { listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")[it - 1] },
                wantToCook = { "I want to cook: $it" },
                changeThis = { "Please change this recipe: $it" },
                recipeBelow = "The recipe, as schema.org Recipe JSON:",
                whatCanIMake = "What can I make with what I have at home? Suggest one recipe I can cook mostly from my pantry.",
                useUpFirst = { "Use these up first, they expire soon: $it." },
                inPantry = { "Also in my pantry: $it." },
                planIntro = "Plan one dinner recipe for each of these days:",
                favourites = { "Recipes I like, for ideas: $it." },
                cookedLately = { "I cooked these lately, so please don't repeat them: $it." },
                alsoWish = { "Also: $it" },
                replyOne = "Reply with exactly one recipe as schema.org Recipe JSON, in a ```json code block.",
                replyPlan = "Reply with a JSON array in one ```json code block: one schema.org Recipe object for each day " +
                    "listed, in the same order. Give each object one extra property, \"$DAY_FIELD\", holding that day's " +
                    "date exactly as listed (YYYY-MM-DD).",
                replyChanged = "Reply with the changed recipe as schema.org Recipe JSON, in a ```json code block, " +
                    "keeping \"isBasedOn\" exactly as it is.",
                recipeFields = "Use @context, @type \"Recipe\", name, description (one sentence), recipeYield (the servings, " +
                    "for example \"4 servings\"), prepTime and cookTime (ISO 8601, for example PT15M), recipeIngredient " +
                    "(a list of strings, each with its amount) and recipeInstructions (a list of HowToStep objects, " +
                    "each with text).",
                unitsAsWritten = "Use the units that suit the recipe.",
                unitsMetric = "Give amounts in metric units (g, kg, ml, l), never cups or ounces.",
                unitsOunces = "Give weights in ounces and pounds, and volumes in cups and spoons.",
                temperatureCelsius = "Give oven temperatures in °C.",
                temperatureFahrenheit = "Give oven temperatures in °F.",
                writeIn = { "Write it in $it." },
                noteOutside = "Put anything else you want to say outside the code block.",
                planMealsIntro = { "Plan one recipe for each of these meals ($it) on each of these days:" },
                savedRecipesIntro = "My saved recipes, one per line as name | link:",
                useSavedOnly = "Use only my saved recipes listed above. Don't write them out: for each one give just " +
                    "@type \"Recipe\", its \"name\", its link exactly as listed in \"isBasedOn\", and its day and meal. " +
                    "Use a recipe more than once only if there are fewer recipes than meals.",
                useSavedMix = {
                    "Use up to $it of my saved recipes listed above and suggest new recipes for the rest. For a saved " +
                        "recipe don't write it out: give just @type \"Recipe\", its \"name\", its link exactly as listed in " +
                        "\"isBasedOn\", and its day and meal. Write every new recipe out in full."
                },
                replyPlanMeals = "Reply with a JSON array in one ```json code block: one schema.org Recipe object for each " +
                    "of these meals on each day listed, day by day in the order listed. Give each object two extra " +
                    "properties: \"$DAY_FIELD\", holding that day's date exactly as listed (YYYY-MM-DD), and " +
                    "\"$MEAL_FIELD\", holding the meal's name exactly as listed."
            )
        }
    }

    /** The [words], and the two Settings choices every prompt follows. */
    data class Setup(
        val words: Words,
        val unitSystem: UnitSystem,
        val temperatureUnit: TemperatureUnit
    ) {
        companion object {
            val ENGLISH = Setup(Words.ENGLISH, UnitSystem.AS_WRITTEN, TemperatureUnit.AS_WRITTEN)
        }
    }

    /** "I want to cook: [request]": the cook's own words. */
    fun describe(request: String, setup: Setup): String =
        lines(setup.words.wantToCook(request.trim()), reply(setup.words.replyOne, setup))

    /**
     * "What can I make?": what is in stock in [pantry], the items [UseItUp.soon] on [today] first;
     * [request] (may be blank) adds the cook's wishes.
     */
    fun whatCanIMake(pantry: List<PantryItem>, today: Long, request: String, setup: Setup): String {
        val inStock = pantry.filter { it.inStock && it.name.isNotBlank() }
        val (soon, rest) = inStock.partition { UseItUp.soon(it, today) }
        val soonest = soon.sortedWith(compareBy({ it.expiresDay }, { it.name.trim().lowercase(Locale.ROOT) }, { it.id }))
        val others = rest.sortedWith(compareBy({ it.name.trim().lowercase(Locale.ROOT) }, { it.id }))
        val words = setup.words
        return lines(
            words.whatCanIMake,
            soonest.takeIf { it.isNotEmpty() }?.let { words.useUpFirst(items(it.take(MAX_PANTRY))) },
            others.takeIf { it.isNotEmpty() && soonest.size < MAX_PANTRY }
                ?.let { words.inPantry(items(it.take(MAX_PANTRY - soonest.size))) },
            wish(request, words),
            reply(words.replyOne, setup)
        )
    }

    /**
     * "Plan my week": one recipe for each of [days] (epoch days), from [favourites] and away from
     * [cooked] (titles). The reply is the meal-plan format in this object's doc.
     */
    fun planMyWeek(days: List<Long>, favourites: List<String>, cooked: List<String>, request: String, setup: Setup): String {
        val words = setup.words
        val listed = days.map { day -> "- ${isoDate(day)} (${words.weekday(PlanDays.dayOfWeek(day))})" }
        return lines(
            words.planIntro,
            listed.joinToString("\n"),
            titles(favourites).takeIf { it.isNotEmpty() }?.let { words.favourites(it) },
            titles(cooked).takeIf { it.isNotEmpty() }?.let { words.cookedLately(it) },
            wish(request, words),
            reply(words.replyPlan, setup)
        )
    }

    /**
     * "Plan my week" with choices (#392, #367): one recipe for each of [choice]'s meals on each of
     * its days, from new recipes, the cook's own (named by link) or a mix; [favourites] as ideas
     * and [cooked] to avoid (titles; the caller leaves a list out by passing none). The reply is the
     * meal-plan format in this object's doc, with `"meal"` too. With no meal, the plain [planMyWeek].
     */
    fun planMyWeekPlus(choice: PlanChoice, favourites: List<String>, cooked: List<String>, request: String, setup: Setup): String {
        val meals = choice.mealNames
        if (meals.isEmpty()) return planMyWeek(choice.days, favourites, cooked, request, setup)
        val words = setup.words
        val listed = choice.days.map { day -> "- ${isoDate(day)} (${words.weekday(PlanDays.dayOfWeek(day))})" }
        val named = choice.named.takeIf { choice.source != PlanSource.NEW }.orEmpty()
        val saved = named.takeIf { it.isNotEmpty() }
            ?.let { words.savedRecipesIntro + "\n" + it.joinToString("\n") { c -> "- ${c.name} | ${c.link}" } }
        val source = when {
            named.isEmpty() -> null
            choice.source == PlanSource.MINE -> words.useSavedOnly
            else -> words.useSavedMix(choice.mix.coerceIn(1, choice.slots.coerceAtLeast(1)))
        }
        return lines(
            words.planMealsIntro(meals.joinToString(", ")),
            listed.joinToString("\n"),
            saved,
            source,
            titles(favourites).takeIf { it.isNotEmpty() }?.let { words.favourites(it) },
            titles(cooked).takeIf { it.isNotEmpty() }?.let { words.cookedLately(it) },
            wish(request, words),
            reply(words.replyPlanMeals, setup, writeOut = !(choice.source == PlanSource.MINE && named.isNotEmpty()))
        )
    }

    /** "Change this recipe": [request], and [recipe] as schema.org JSON ([recipeJson]). */
    fun changeRecipe(recipe: Recipe, request: String, setup: Setup): String {
        val words = setup.words
        return lines(
            words.changeThis(request.trim()),
            words.recipeBelow,
            "```json\n${recipeJson(recipe)}\n```",
            reply(words.replyChanged, setup)
        )
    }

    /**
     * [recipe] as schema.org Recipe JSON, written by hand so both apps print the same bytes: two
     * spaces to indent, the keys in a fixed order, a step on one line. `isBasedOn` is its
     * `sourceUrl`; times and yield stay as stored; an empty field is left out.
     */
    fun recipeJson(recipe: Recipe): String {
        val fields = mutableListOf<String>()
        fun text(key: String, value: String?) {
            if (!value.isNullOrBlank()) fields += "  ${quote(key)}: ${quote(value)}"
        }
        fields += "  \"@context\": \"https://schema.org\""
        fields += "  \"@type\": \"Recipe\""
        text("name", recipe.name)
        text("isBasedOn", recipe.sourceUrl)
        text("inLanguage", recipe.language)
        text("recipeYield", recipe.yield)
        text("prepTime", recipe.prepTime)
        text("cookTime", recipe.cookTime)
        text("totalTime", recipe.totalTime)
        fields += "  \"recipeIngredient\": " + array(recipe.ingredients.map { "    ${quote(it)}" })
        fields += "  \"recipeInstructions\": " +
            array(recipe.instructions.map { "    {\"@type\": \"HowToStep\", \"text\": ${quote(it)}}" })
        return "{\n${fields.joinToString(",\n")}\n}"
    }

    /** [day] (an epoch day) as `YYYY-MM-DD`. */
    fun isoDate(day: Long): String {
        val (year, month, dayOfMonth) = PlanDays.civil(day)
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, dayOfMonth)
    }

    private fun array(items: List<String>): String =
        if (items.isEmpty()) "[]" else "[\n${items.joinToString(",\n")}\n  ]"

    /** A JSON string: `"` and `\` escaped, control characters as `\n`, `\r`, `\t` or `\u00xx`. */
    private fun quote(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun item(item: PantryItem): String {
        val name = item.name.trim()
        val quantity = item.quantity?.trim()?.takeIf { it.isNotEmpty() }
        return if (quantity == null) name else "$name ($quantity)"
    }

    private fun items(items: List<PantryItem>): String = items.joinToString(", ") { item(it) }

    private fun titles(titles: List<String>): String =
        titles.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_TITLES).joinToString(", ")

    private fun wish(request: String, words: Words): String? =
        request.trim().takeIf { it.isNotEmpty() }?.let { words.alsoWish(it) }

    /**
     * The sentence asking for the JSON, then the fields, the units, the temperature, the language.
     * With [writeOut] false (only saved recipes, named by link) the fields, units and temperature
     * are left out: nothing is written out.
     */
    private fun reply(ask: String, setup: Setup, writeOut: Boolean = true): String {
        val words = setup.words
        val units = when (setup.unitSystem) {
            UnitSystem.AS_WRITTEN -> words.unitsAsWritten
            UnitSystem.METRIC -> words.unitsMetric
            UnitSystem.OUNCES -> words.unitsOunces
        }
        val temperature = when (setup.temperatureUnit) {
            TemperatureUnit.AS_WRITTEN -> null
            TemperatureUnit.CELSIUS -> words.temperatureCelsius
            TemperatureUnit.FAHRENHEIT -> words.temperatureFahrenheit
        }
        return if (writeOut) {
            listOfNotNull(ask, words.recipeFields, units, temperature, words.writeIn(words.language), words.noteOutside)
        } else {
            listOf(ask, words.writeIn(words.language), words.noteOutside)
        }.joinToString(" ")
    }

    private fun lines(vararg parts: String?): String = parts.filterNotNull().joinToString("\n\n")
}
