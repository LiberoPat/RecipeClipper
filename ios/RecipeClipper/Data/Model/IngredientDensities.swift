import Foundation

/// `gramsPerCup` is per US cup (236.6 ml); nil means "recognised, but deliberately not
/// converted" (ingredients whose weight varies too much to state a number honestly).
/// `liquid` means pourable, and is what the "convert liquids too" option controls.
/// `stickable` allows the "stick" unit (butter and margarine only).
struct Density: Equatable {
    let gramsPerCup: Double?
    let liquid: Bool
    var stickable: Bool = false
}

/// Approximate weights of common baking ingredients. Dry goods follow King Arthur Baking's
/// published ingredient weight chart (spooned-and-levelled cups); liquids and fats use
/// physical densities (USDA), so a cup of milk is 245 g rather than a rounded 227 g.
///
/// Only ingredients that weigh roughly the same every time belong here. Salt (table vs
/// kosher differ ~2x), chopped produce, shredded cheese, nuts, rolled oats and rice
/// (cooked vs raw) are left out on purpose, so those lines stay as written.
enum IngredientDensities {

    private struct Entry {
        let aliases: [String]
        let density: Density
    }

    private static func dry(_ gramsPerCup: Double, _ aliases: String...) -> Entry {
        Entry(aliases: aliases, density: Density(gramsPerCup: gramsPerCup, liquid: false))
    }

    private static func liquid(_ gramsPerCup: Double, _ aliases: String...) -> Entry {
        Entry(aliases: aliases, density: Density(gramsPerCup: gramsPerCup, liquid: true))
    }

    private static func butter(_ gramsPerCup: Double, _ aliases: String...) -> Entry {
        Entry(aliases: aliases, density: Density(gramsPerCup: gramsPerCup, liquid: false, stickable: true))
    }

    /// Matches by name but converts nothing; beats a shorter alias like plain "flour".
    private static func skip(_ aliases: String...) -> Entry {
        Entry(aliases: aliases, density: Density(gramsPerCup: nil, liquid: false))
    }

    private static let entries: [Entry] = [
        // Flours and starches
        dry(120.0, "all purpose flour", "ap flour", "plain flour", "bread flour", "flour"),
        dry(114.0, "cake flour"),
        dry(106.0, "pastry flour"),
        dry(113.0, "whole wheat flour", "wholemeal flour"),
        dry(96.0, "almond flour", "almond meal", "ground almonds"),
        dry(92.0, "oat flour"),
        dry(138.0, "cornmeal"),
        dry(112.0, "cornstarch", "corn starch"),
        skip(
            "rice flour", "coconut flour", "corn flour", "cornflour", "chickpea flour",
            "gram flour", "tapioca flour", "potato flour", "gluten free flour",
            "gluten free all purpose flour"
        ),

        // Sugars
        dry(200.0, "granulated sugar", "white sugar", "caster sugar", "castor sugar", "superfine sugar", "sugar"),
        dry(213.0, "brown sugar"), // packed, the convention in recipes
        dry(113.0, "powdered sugar", "confectioners sugar", "icing sugar"),

        // Baking staples
        dry(84.0, "cocoa powder", "cocoa", "unsweetened cocoa"),
        dry(192.0, "baking powder"),
        dry(288.0, "baking soda", "bicarbonate of soda"),
        dry(170.0, "chocolate chips", "chocolate chunks"),

        // Fats and spreads
        butter(227.0, "butter", "margarine"),
        dry(260.0, "peanut butter", "almond butter", "cashew butter", "nut butter"),
        skip("apple butter", "cocoa butter", "shea butter"),

        // Dairy that isn't pourable
        dry(230.0, "sour cream"),
        dry(245.0, "yogurt", "greek yogurt", "plain yogurt"),

        // Pourable
        liquid(237.0, "water"),
        liquid(245.0, "milk", "whole milk", "skim milk", "buttermilk"),
        liquid(
            238.0, "heavy cream", "heavy whipping cream", "whipping cream", "double cream",
            "cream", "light cream", "single cream"
        ),
        // Bare "cream" is pourable; these end in "cream" but are not, or vary too much.
        skip("ice cream", "whipped cream", "coconut cream", "clotted cream"),
        liquid(242.0, "half and half"),
        liquid(
            218.0, "oil", "olive oil", "vegetable oil", "canola oil", "sunflower oil",
            "avocado oil", "coconut oil"
        ),
        liquid(340.0, "honey"),
        liquid(315.0, "maple syrup"),
        liquid(240.0, "broth", "stock", "coffee", "beer"),
        liquid(239.0, "vinegar"),
        liquid(236.0, "wine"),
        liquid(245.0, "juice"),
        skip(
            "condensed milk", "sweetened condensed milk", "milk powder", "powdered milk",
            "dry milk"
        ),
    ]

    // Longest alias first, so "brown sugar" wins over "sugar" and "peanut butter" over "butter".
    // Ties keep table order (Kotlin's sortedByDescending is stable; the index makes it so here).
    private static let aliases: [(alias: String, density: Density)] = entries
        .flatMap { entry in entry.aliases.map { (alias: $0, density: entry.density) } }
        .enumerated()
        .sorted { a, b in
            a.element.alias.count != b.element.alias.count
                ? a.element.alias.count > b.element.alias.count
                : a.offset < b.offset
        }
        .map { $0.element }

    private static let trailingModifiers: Set<String> = [
        "packed", "sifted", "unsifted", "softened", "melted", "divided", "cold", "chilled",
        "warm", "lukewarm", "hot", "room", "temperature", "at", "optional",
    ]

    private static let innermostParens = JRegex(#"\([^()]*\)"#)
    private static let whitespace = JRegex(#"\s+"#)

    /// Looks the ingredient up by the *end* of its name, so "unsalted butter" and "light
    /// brown sugar" match while "butter beans" and "flour tortillas" don't.
    static func find(_ ingredientText: String) -> Density? {
        let phrase = headPhrase(ingredientText)
        return aliases.first { phrase == $0.alias || phrase.hasSuffix(" " + $0.alias) }?.density
    }

    /// Removes parenthesised text, including nested or doubled parentheses ("((all-purpose
    /// flour))"), innermost first until nothing changes, then drops any unmatched paren.
    private static func stripParentheses(_ text: String) -> String {
        var current = text
        while true {
            let next = innermostParens.replace(current, with: " ")
            if next == current { break }
            current = next
        }
        return current.replacingOccurrences(of: "(", with: " ").replacingOccurrences(of: ")", with: " ")
    }

    /// The ingredient name: text before the first comma, without parentheses or modifiers.
    private static func headPhrase(_ text: String) -> String {
        var s = stripParentheses(text)
        if let comma = s.firstIndex(of: ",") { s = String(s[..<comma]) }
        s = s.lowercased()
            .replacingOccurrences(of: "'", with: "")
            .replacingOccurrences(of: "’", with: "")
            .replacingOccurrences(of: "-", with: " ")
        var words = whitespace.split(s).filter { !$0.isEmpty }
        while let last = words.last, trailingModifiers.contains(last) { words.removeLast() }
        return words.joined(separator: " ")
    }
}
