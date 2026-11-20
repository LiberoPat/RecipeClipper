import Foundation

/// The prompts "Ask an AI" copies or shares (#351, #353). No AI runs in the app: the cook pastes
/// the prompt into any chat and shares the answer back in (#350). Every prompt asks for schema.org
/// Recipe JSON in a ```json block, with the servings, the units and the oven temperature the cook
/// chose in Settings, in the UI language, so the replies come back in one shape. Android's
/// `AskAiPrompt`, word for word.
///
/// Pure. The words come in as `Words`, which the view reads from the string catalog
/// (`Strings.askAiWords`); `Words.english` is the default for tests. The cook's own text (a
/// request, a pantry item, a title) goes in as typed. The lists are capped (`maxPantry`,
/// `maxTitles`) so one prompt stays a sensible size.
///
/// The meal-plan format ("Plan my week", read by #352) is a JSON array of schema.org Recipe
/// objects, one per listed day, each with one extra property, `dayField` ("day"), holding that
/// day's date as `YYYY-MM-DD`, exactly as the prompt listed it.
///
/// Plan my week with choices (#392, #367, `planMyWeekPlus`) asks for one recipe for each chosen meal
/// on each day, each object carrying `"day"` and `"meal"` (the meal's name exactly as listed). From
/// the cook's own recipes it lists up to `maxCandidates` of them, most recently viewed first, as
/// `name | link` (`PlanCandidate`: the link is the recipe's `sourceUrl`, as `isBasedOn` in "Change
/// this recipe") and asks the AI to name a saved recipe by that link instead of writing it out; the
/// reader (`MealPlanImport`) finds it again.
///
/// "Change this recipe" sends the recipe as schema.org JSON with `isBasedOn` set to the recipe's
/// `sourceUrl`: the one id every recipe has, unique and never changing (the cleaned link of a
/// clipped page, `manual:<uuid>` for a typed-in one), and a URL, which is what schema.org's
/// `isBasedOn` takes. #354 finds the original by it.
enum AskAiPrompt {
    /// The meal plan's extra property: the day's ISO date.
    static let dayField = "day"
    /// At most this many pantry items go into a prompt, the ones to use up first always.
    static let maxPantry = 60
    /// At most this many favourites, and this many recently cooked recipes, go into "Plan my week".
    static let maxTitles = 15
    /// The days "Plan my week" asks for: a week from the start day.
    static let planDays = Int(PlanDays.blockDays)
    /// The meal plan's second extra property (#392): the meal's name, as the prompt listed it.
    static let mealField = "meal"
    /// At most this many of the cook's recipes are named in a plan prompt (#367), most recently viewed first.
    static let maxCandidates = 40
    /// More than this many recipes to write out make an answer chat apps may cut off (#392): the sheet warns.
    static let longAnswer = 14

    /// Where a week's recipes come from (#367).
    enum PlanSource: String, Equatable { case new = "NEW", mine = "MINE", mix = "MIX" }

    /// A saved recipe offered to the AI: its `name` and its `link` (the recipe's `sourceUrl`).
    struct PlanCandidate: Equatable {
        var name: String
        var link: String
    }

    /// What the plan sheet chose (#392, #367): the `days` (epoch days, from the start day), the `meals`
    /// (the meal types' names, in the cook's order, as listed in the prompt), where the recipes come
    /// from, the `candidates` to name (most recently viewed first) and, for a mix, how many of the
    /// cook's recipes at most (`mix`).
    struct PlanChoice {
        var days: [Int64]
        var meals: [String]
        var source: PlanSource = .new
        var candidates: [PlanCandidate] = []
        var mix = 0

        /// The meals asked for, trimmed, blanks and repeats left out.
        var mealNames: [String] {
            var seen = Set<String>()
            return meals.map(AskAiPrompt.trim).filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
        }

        /// The recipes asked for: every meal on every day.
        var slots: Int { days.count * mealNames.count }

        /// The candidates actually named: usable ones, at most `maxCandidates`.
        var named: [PlanCandidate] {
            candidates.map { PlanCandidate(name: AskAiPrompt.trim($0.name), link: AskAiPrompt.trim($0.link)) }
                .filter { !$0.name.isEmpty && !$0.link.isEmpty }
                .prefix(AskAiPrompt.maxCandidates).map { $0 }
        }

        /// The recipes the AI has to write out, which is what makes an answer long.
        var writtenOut: Int { AskAiPrompt.recipesToWrite(source, slots, mix) }

        /// The answer may be long enough for a chat app to cut it off.
        var isLongAnswer: Bool { writtenOut > AskAiPrompt.longAnswer }
    }

    /// How many of a week's `slots` meals the AI writes out in full: all of them for new recipes, none
    /// from the cook's own (named by link), and for a `mix` the rest beyond the cook's.
    static func recipesToWrite(_ source: PlanSource, _ slots: Int, _ mix: Int) -> Int {
        switch source {
        case .new: return slots
        case .mine: return 0
        case .mix: return max(0, slots - min(max(mix, 1), max(slots, 1)))
        }
    }

    /// The recipes of `summaries` as candidates to name (#367), most recently viewed first (the newest
    /// id first among equals): the tour's sample and any without a name or link are left out. The
    /// prompt names the first `maxCandidates`; the sheet counts them all.
    static func candidates(_ summaries: [RecipeSummary]) -> [PlanCandidate] {
        summaries
            .filter { !trim($0.title).isEmpty && !trim($0.sourceUrl).isEmpty && !SampleRecipe.isSample($0.sourceUrl) }
            .sorted { a, b in a.lastViewedAt != b.lastViewedAt ? a.lastViewedAt > b.lastViewedAt : a.id > b.id }
            .map { PlanCandidate(name: $0.title, link: $0.sourceUrl) }
    }

    /// The words every prompt is made of, in the UI language. `language` is that language's own
    /// name ("Deutsch"), for "write it in …". `weekday` names 1 = Sunday … 7 = Saturday
    /// (`PlanDays.dayOfWeek`). The `reply…` lines are the sentence that asks for the JSON block.
    struct Words {
        var language: String
        var weekday: (Int) -> String
        var wantToCook: (_ request: String) -> String
        var changeThis: (_ request: String) -> String
        var recipeBelow: String
        var whatCanIMake: String
        var useUpFirst: (_ items: String) -> String
        var inPantry: (_ items: String) -> String
        var planIntro: String
        var favourites: (_ titles: String) -> String
        var cookedLately: (_ titles: String) -> String
        var alsoWish: (_ request: String) -> String
        var replyOne: String
        var replyPlan: String
        var replyChanged: String
        var recipeFields: String
        var unitsAsWritten: String
        var unitsMetric: String
        var unitsOunces: String
        var temperatureCelsius: String
        var temperatureFahrenheit: String
        var writeIn: (_ language: String) -> String
        var noteOutside: String
        /// "Plan one recipe for each of these meals (Breakfast, Dinner) on each of these days:" (#392).
        var planMealsIntro: (_ meals: String) -> String
        /// Heads the saved recipes, one `name | link` per line (#367).
        var savedRecipesIntro: String
        /// From my recipes only: name them by link, don't write them out.
        var useSavedOnly: String
        /// A mix: up to this many saved ones by link, new ones written out.
        var useSavedMix: (_ count: Int) -> String
        /// The reply format with a `"day"` and a `"meal"` on each object (#392).
        var replyPlanMeals: String

        static let english = Words(
            language: "English",
            weekday: { ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"][$0 - 1] },
            wantToCook: { "I want to cook: \($0)" },
            changeThis: { "Please change this recipe: \($0)" },
            recipeBelow: "The recipe, as schema.org Recipe JSON:",
            whatCanIMake: "What can I make with what I have at home? Suggest one recipe I can cook mostly from my pantry.",
            useUpFirst: { "Use these up first, they expire soon: \($0)." },
            inPantry: { "Also in my pantry: \($0)." },
            planIntro: "Plan one dinner recipe for each of these days:",
            favourites: { "Recipes I like, for ideas: \($0)." },
            cookedLately: { "I cooked these lately, so please don't repeat them: \($0)." },
            alsoWish: { "Also: \($0)" },
            replyOne: "Reply with exactly one recipe as schema.org Recipe JSON, in a ```json code block.",
            replyPlan: "Reply with a JSON array in one ```json code block: one schema.org Recipe object for each day "
                + "listed, in the same order. Give each object one extra property, \"\(dayField)\", holding that day's "
                + "date exactly as listed (YYYY-MM-DD).",
            replyChanged: "Reply with the changed recipe as schema.org Recipe JSON, in a ```json code block, "
                + "keeping \"isBasedOn\" exactly as it is.",
            recipeFields: "Use @context, @type \"Recipe\", name, description (one sentence), recipeYield (the servings, "
                + "for example \"4 servings\"), prepTime and cookTime (ISO 8601, for example PT15M), recipeIngredient "
                + "(a list of strings, each with its amount) and recipeInstructions (a list of HowToStep objects, "
                + "each with text).",
            unitsAsWritten: "Use the units that suit the recipe.",
            unitsMetric: "Give amounts in metric units (g, kg, ml, l), never cups or ounces.",
            unitsOunces: "Give weights in ounces and pounds, and volumes in cups and spoons.",
            temperatureCelsius: "Give oven temperatures in °C.",
            temperatureFahrenheit: "Give oven temperatures in °F.",
            writeIn: { "Write it in \($0)." },
            noteOutside: "Put anything else you want to say outside the code block.",
            planMealsIntro: { "Plan one recipe for each of these meals (\($0)) on each of these days:" },
            savedRecipesIntro: "My saved recipes, one per line as name | link:",
            useSavedOnly: "Use only my saved recipes listed above. Don't write them out: for each one give just "
                + "@type \"Recipe\", its \"name\", its link exactly as listed in \"isBasedOn\", and its day and meal. "
                + "Use a recipe more than once only if there are fewer recipes than meals.",
            useSavedMix: {
                "Use up to \($0) of my saved recipes listed above and suggest new recipes for the rest. For a saved "
                    + "recipe don't write it out: give just @type \"Recipe\", its \"name\", its link exactly as listed in "
                    + "\"isBasedOn\", and its day and meal. Write every new recipe out in full."
            },
            replyPlanMeals: "Reply with a JSON array in one ```json code block: one schema.org Recipe object for each "
                + "of these meals on each day listed, day by day in the order listed. Give each object two extra "
                + "properties: \"\(dayField)\", holding that day's date exactly as listed (YYYY-MM-DD), and "
                + "\"\(mealField)\", holding the meal's name exactly as listed."
        )
    }

    /// The `words`, and the two Settings choices every prompt follows.
    struct Setup {
        var words: Words
        var unitSystem: UnitSystem
        var temperatureUnit: TemperatureUnit

        static let english = Setup(words: .english, unitSystem: .asWritten, temperatureUnit: .asWritten)
    }

    /// "I want to cook: `request`": the cook's own words.
    static func describe(_ request: String, setup: Setup) -> String {
        lines(setup.words.wantToCook(trim(request)), reply(setup.words.replyOne, setup))
    }

    /// "What can I make?": what is in stock in `pantry`, the items `UseItUp.soon` on `today`
    /// first; `request` (may be blank) adds the cook's wishes.
    static func whatCanIMake(pantry: [PantryItem], today: Int64, request: String, setup: Setup) -> String {
        let inStock = pantry.filter { $0.inStock && !trim($0.name).isEmpty }
        let soon = inStock.filter { UseItUp.soon($0, today: today) }
        let rest = inStock.filter { !UseItUp.soon($0, today: today) }
        let soonest = soon.sorted { a, b in
            if a.expiresDay != b.expiresDay { return (a.expiresDay ?? 0) < (b.expiresDay ?? 0) }
            let la = utf16(trim(a.name).lowercased()), lb = utf16(trim(b.name).lowercased())
            if la != lb { return la.lexicographicallyPrecedes(lb) }
            return a.id < b.id
        }
        let others = rest.sorted { a, b in
            let la = utf16(trim(a.name).lowercased()), lb = utf16(trim(b.name).lowercased())
            if la != lb { return la.lexicographicallyPrecedes(lb) }
            return a.id < b.id
        }
        let words = setup.words
        return lines(
            words.whatCanIMake,
            soonest.isEmpty ? nil : words.useUpFirst(items(Array(soonest.prefix(maxPantry)))),
            others.isEmpty || soonest.count >= maxPantry
                ? nil : words.inPantry(items(Array(others.prefix(maxPantry - soonest.count)))),
            wish(request, words),
            reply(words.replyOne, setup)
        )
    }

    /// "Plan my week": one recipe for each of `days` (epoch days), from `favourites` and away
    /// from `cooked` (titles). The reply is the meal-plan format in this type's doc.
    static func planMyWeek(days: [Int64], favourites: [String], cooked: [String], request: String, setup: Setup) -> String {
        let words = setup.words
        let listed = days.map { "- \(isoDate($0)) (\(words.weekday(PlanDays.dayOfWeek($0))))" }
        let liked = titles(favourites), recent = titles(cooked)
        return lines(
            words.planIntro,
            listed.joined(separator: "\n"),
            liked.isEmpty ? nil : words.favourites(liked),
            recent.isEmpty ? nil : words.cookedLately(recent),
            wish(request, words),
            reply(words.replyPlan, setup)
        )
    }

    /// "Plan my week" with choices (#392, #367): one recipe for each of `choice`'s meals on each of its
    /// days, from new recipes, the cook's own (named by link) or a mix; `favourites` as ideas and
    /// `cooked` to avoid (titles; the caller leaves a list out by passing none). The reply is the
    /// meal-plan format in this type's doc, with `"meal"` too. With no meal, the plain `planMyWeek`.
    static func planMyWeekPlus(
        _ choice: PlanChoice, favourites: [String], cooked: [String], request: String, setup: Setup
    ) -> String {
        let meals = choice.mealNames
        if meals.isEmpty {
            return planMyWeek(days: choice.days, favourites: favourites, cooked: cooked, request: request, setup: setup)
        }
        let words = setup.words
        let listed = choice.days.map { "- \(isoDate($0)) (\(words.weekday(PlanDays.dayOfWeek($0))))" }
        let named = choice.source == .new ? [] : choice.named
        let saved: String? = named.isEmpty
            ? nil : words.savedRecipesIntro + "\n" + named.map { "- \($0.name) | \($0.link)" }.joined(separator: "\n")
        let source: String?
        if named.isEmpty {
            source = nil
        } else if choice.source == .mine {
            source = words.useSavedOnly
        } else {
            source = words.useSavedMix(min(max(choice.mix, 1), max(choice.slots, 1)))
        }
        let liked = titles(favourites), recent = titles(cooked)
        return lines(
            words.planMealsIntro(meals.joined(separator: ", ")),
            listed.joined(separator: "\n"),
            saved,
            source,
            liked.isEmpty ? nil : words.favourites(liked),
            recent.isEmpty ? nil : words.cookedLately(recent),
            wish(request, words),
            reply(words.replyPlanMeals, setup, writeOut: !(choice.source == .mine && !named.isEmpty))
        )
    }

    /// "Change this recipe": `request`, and `recipe` as schema.org JSON (`recipeJson`).
    static func changeRecipe(_ recipe: Recipe, request: String, setup: Setup) -> String {
        let words = setup.words
        return lines(
            words.changeThis(trim(request)),
            words.recipeBelow,
            "```json\n\(recipeJson(recipe))\n```",
            reply(words.replyChanged, setup)
        )
    }

    /// `recipe` as schema.org Recipe JSON, written by hand so both apps print the same bytes: two
    /// spaces to indent, the keys in a fixed order, a step on one line. `isBasedOn` is its
    /// `sourceUrl`; times and yield stay as stored; an empty field is left out.
    static func recipeJson(_ recipe: Recipe) -> String {
        var fields: [String] = []
        func text(_ key: String, _ value: String?) {
            if let value, !trim(value).isEmpty { fields.append("  \(quote(key)): \(quote(value))") }
        }
        fields.append("  \"@context\": \"https://schema.org\"")
        fields.append("  \"@type\": \"Recipe\"")
        text("name", recipe.name)
        text("isBasedOn", recipe.sourceUrl)
        text("inLanguage", recipe.language)
        text("recipeYield", recipe.yield)
        text("prepTime", recipe.prepTime)
        text("cookTime", recipe.cookTime)
        text("totalTime", recipe.totalTime)
        fields.append("  \"recipeIngredient\": " + array(recipe.ingredients.map { "    \(quote($0))" }))
        fields.append("  \"recipeInstructions\": "
            + array(recipe.instructions.map { "    {\"@type\": \"HowToStep\", \"text\": \(quote($0))}" }))
        return "{\n\(fields.joined(separator: ",\n"))\n}"
    }

    /// `day` (an epoch day) as `YYYY-MM-DD`.
    static func isoDate(_ day: Int64) -> String {
        let (year, month, dayOfMonth) = PlanDays.civil(day)
        return String(format: "%04ld-%02ld-%02ld", year, month, dayOfMonth)
    }

    private static func array(_ items: [String]) -> String {
        items.isEmpty ? "[]" : "[\n\(items.joined(separator: ",\n"))\n  ]"
    }

    /// A JSON string: `"` and `\` escaped, control characters as `\n`, `\r`, `\t` or `\u00xx`.
    private static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }

    private static func item(_ item: PantryItem) -> String {
        let name = trim(item.name)
        guard let quantity = item.quantity.map(trim), !quantity.isEmpty else { return name }
        return "\(name) (\(quantity))"
    }

    private static func items(_ items: [PantryItem]) -> String { items.map(item).joined(separator: ", ") }

    private static func titles(_ titles: [String]) -> String {
        titles.map(trim).filter { !$0.isEmpty }.prefix(maxTitles).joined(separator: ", ")
    }

    private static func wish(_ request: String, _ words: Words) -> String? {
        let wish = trim(request)
        return wish.isEmpty ? nil : words.alsoWish(wish)
    }

    /// The sentence asking for the JSON, then the fields, the units, the temperature, the language.
    /// With `writeOut` false (only saved recipes, named by link) the fields, units and temperature
    /// are left out: nothing is written out.
    private static func reply(_ ask: String, _ setup: Setup, writeOut: Bool = true) -> String {
        let words = setup.words
        let units: String
        switch setup.unitSystem {
        case .asWritten: units = words.unitsAsWritten
        case .metric: units = words.unitsMetric
        case .ounces: units = words.unitsOunces
        }
        let temperature: String?
        switch setup.temperatureUnit {
        case .asWritten: temperature = nil
        case .celsius: temperature = words.temperatureCelsius
        case .fahrenheit: temperature = words.temperatureFahrenheit
        }
        let parts: [String?] = writeOut
            ? [ask, words.recipeFields, units, temperature, words.writeIn(words.language), words.noteOutside]
            : [ask, words.writeIn(words.language), words.noteOutside]
        return parts.compactMap { $0 }.joined(separator: " ")
    }

    private static func lines(_ parts: String?...) -> String { parts.compactMap { $0 }.joined(separator: "\n\n") }

    private static func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    private static func utf16(_ s: String) -> [UInt16] { Array(s.utf16) }
}
