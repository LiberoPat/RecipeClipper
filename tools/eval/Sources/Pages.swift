import Foundation

/// The cached site-check pages, each with its JSON-LD recipe as gold. The model only ever sees
/// the page's visible text (`PageTextReader` leaves scripts out), as if it had no recipe data.
struct EvalPage {
    let file: String
    let url: String
    let html: String
    let gold: Recipe

    private static let ldJson = try! NSRegularExpression(
        pattern: #"<script[^>]*application/ld\+json[^>]*>(.*?)</script>"#, options: [.caseInsensitive, .dotMatchesLineSeparators])

    static func load(_ dir: String) -> [EvalPage] {
        let index = (try? String(contentsOfFile: "\(dir)/index.txt", encoding: .utf8)) ?? ""
        var pages: [EvalPage] = []
        for entry in index.split(separator: "\n") {
            let parts = entry.split(separator: " ").map(String.init)
            guard parts.count >= 3, parts[1] == "200" else { continue }
            let file = "\(dir)/\(String(format: "%02d", Int(parts[0])!)).html"
            guard let html = try? String(contentsOfFile: file, encoding: .utf8) else { continue }
            let ns = html as NSString
            let blocks = ldJson.matches(in: html, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range(at: 1)) }
            guard let gold = JsonLdRecipeParser.parse(blocks, sourceUrl: parts[2]) ?? MicrodataRecipeParser.parse(html: html, sourceUrl: parts[2])
            else { continue }
            pages.append(EvalPage(file: file, url: parts[2], html: html, gold: gold))
        }
        if let html = try? String(contentsOfFile: "../../shared/fixtures/pages/blog-no-recipe-data.html", encoding: .utf8) {
            // The one real no-recipe-data page; its gold is the recipe card as a reader sees it.
            let gold = Recipe(
                name: "Grandma’s Banana Bread",
                ingredients: ["3 very ripe bananas, mashed", "⅓ cup melted butter", "¾ cup sugar", "1 large egg, beaten",
                              "1 teaspoon vanilla extract", "1 teaspoon baking soda", "Pinch of salt", "1 ½ cups all-purpose flour"],
                instructions: [
                    "Preheat the oven to 350°F (175°C) and butter a 4x8-inch loaf pan.",
                    "In a mixing bowl, mash the ripe bananas with a fork until smooth. Stir in the melted butter.",
                    "Mix in the baking soda and salt. Stir in the sugar, beaten egg and vanilla extract.",
                    "Mix in the flour.",
                    "Pour the batter into the prepared loaf pan. Bake for 55 to 65 minutes, until a tester comes out clean.",
                    "Remove from the oven and let cool in the pan for a few minutes. Then remove and cool on a rack.",
                ],
                sourceUrl: "fixture:blog-no-recipe-data")
            pages.append(EvalPage(file: "fixture", url: "fixture:blog-no-recipe-data", html: html, gold: gold))
        }
        return pages
    }

    /// Folded for comparison: NFKC ("½" is "1⁄2"), the fraction slash as "/", every character but
    /// letters, digits, "/" and "." as a space (checkboxes, brackets, commas), lowercase, and each
    /// unit by meaning, not spelling (#128): "c." is "cups" is "cup", "T" "Tbsp." "tablespoons",
    /// "oz." "ounces". A one-letter unit ("c", "t", "T", "g", "l") only after a number.
    static func fold(_ s: String) -> String {
        let t = s.precomposedStringWithCompatibilityMapping.replacingOccurrences(of: "⁄", with: "/")
        let kept = t.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || $0 == "/" || $0 == "." ? Character($0) : " " }
        var out: [String] = []
        for word in String(kept).split(separator: " ").map(String.init) {
            let afterNumber = out.last.map { $0.first?.isNumber == true } ?? false
            let bare = word.hasSuffix(".") && word.count > 1 ? String(word.dropLast()) : word
            if afterNumber, bare == "T" { out.append("tbsp"); continue }
            if afterNumber, bare == "t" { out.append("tsp"); continue }
            let lower = bare.lowercased()
            if let unit = units[lower], afterNumber || lower.count > 1 { out.append(unit); continue }
            out.append(word.lowercased())
        }
        return out.joined(separator: " ")
    }

    /// Unit spellings, by the one each means.
    private static let units: [String: String] = {
        let spellings: [String: [String]] = [
            "cup": ["c", "cup", "cups"],
            "tbsp": ["tbsp", "tbsps", "tbs", "tbl", "tbls", "tablespoon", "tablespoons"],
            "tsp": ["tsp", "tsps", "teaspoon", "teaspoons"],
            "oz": ["oz", "ozs", "ounce", "ounces"],
            "lb": ["lb", "lbs", "pound", "pounds"],
            "g": ["g", "gr", "gram", "grams", "gramme", "grammes"],
            "kg": ["kg", "kgs", "kilogram", "kilograms"],
            "ml": ["ml", "milliliter", "milliliters", "millilitre", "millilitres"],
            "l": ["l", "liter", "liters", "litre", "litres"],
            "pt": ["pt", "pint", "pints"],
            "qt": ["qt", "quart", "quarts"],
        ]
        var map: [String: String] = [:]
        for (unit, words) in spellings { for w in words { map[w] = unit } }
        return map
    }()

    /// A kept line is right when it is a gold line, or the start of one (the card or the JSON-LD
    /// adds a note the other lacks) of at least three words; a step may also sit inside a longer
    /// gold step, or hold a shorter one, of at least four words (sites split the method differently).
    static func matches(_ kept: String, _ gold: [String], step: Bool = false) -> Bool {
        let k = fold(kept)
        return gold.contains { g in
            let f = fold(g)
            if f == k { return true }
            let (short, long) = f.count < k.count ? (f, k) : (k, f)
            let words = short.split(separator: " ").count
            if step { return words >= 4 && long.contains(short) }
            return words >= 3 && long.hasPrefix(short + " ")
        }
    }
}
