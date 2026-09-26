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

    /// Folded for comparison: NFKC ("½" is "1⁄2"), the fraction slash as "/", then `Norm.text`.
    static func fold(_ s: String) -> String {
        Norm.text(s.precomposedStringWithCompatibilityMapping.replacingOccurrences(of: "⁄", with: "/")
            .replacingOccurrences(of: "\u{a0}", with: " ")) ?? ""
    }

    /// A kept line is right when it is a gold line, or one holds the other and the shorter is at
    /// least 60% of the longer (sites add or drop a trailing note between the card and JSON-LD).
    static func matches(_ kept: String, _ gold: [String]) -> Bool {
        let k = fold(kept)
        return gold.contains { g in
            let f = fold(g)
            if f == k { return true }
            let (short, long) = f.count < k.count ? (f, k) : (k, f)
            return !short.isEmpty && long.contains(short) && Double(short.count) >= 0.6 * Double(long.count)
        }
    }
}
