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
    /// The days "Plan my week" asks for: a week from today.
    static let planDays = Int(PlanDays.blockDays)

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
            noteOutside: "Put anything else you want to say outside the code block."
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
    private static func reply(_ ask: String, _ setup: Setup) -> String {
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
        return [ask, words.recipeFields, units, temperature, words.writeIn(words.language), words.noteOutside]
            .compactMap { $0 }.joined(separator: " ")
    }

    private static func lines(_ parts: String?...) -> String { parts.compactMap { $0 }.joined(separator: "\n\n") }

    private static func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    private static func utf16(_ s: String) -> [UInt16] { Array(s.utf16) }
}
