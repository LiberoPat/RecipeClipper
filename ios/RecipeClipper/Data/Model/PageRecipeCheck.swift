import Foundation

/// The gate between the on-device model and the screen for a page with no recipe data (#103):
/// the model may only pick text that is on the page, never write any. Pure; Android's
/// `PageRecipeCheck`, pinned by the differential corpus's `Pick` rows. It works on UTF-16 units,
/// as Kotlin's strings are, so both find the same spans.
///
/// Each pick is looked for in the text the model was given, both folded the same way (NFKC,
/// typographic quotes, dashes and the fraction slash as ASCII, lowercase, whitespace runs as one
/// space). What shows is the page's own text for the span. A span never cuts into a word or a
/// number; an ingredient starts its line (after a bullet); a name, ingredient or step holds a
/// letter. The rest is dropped; a recipe needs a name plus ingredients or steps.
///
/// One recipe's lines only (#128): a page can hold a second recipe, whose lines are on the page
/// too. The text is cut into cards at each ingredients heading that follows a steps heading
/// ("Ingredients … Directions … Ingredients"), and only the ingredients and steps found in the
/// card holding the most of them are kept (a tie goes to the earlier card).
enum PageRecipeCheck {

    enum Kind: String { case name, ingredient, step, other }

    /// The verified recipe, with the page's own text for every field kept; nil if too little is left.
    static func verify(_ page: String, _ picked: PageSelection) -> PageSelection? {
        let folded = Folded(page)
        guard let name = picked.name.flatMap({ find(folded, $0, .name) }) else { return nil }
        let foundIngredients = picked.ingredients.map { spans(folded, $0, .ingredient) }
        let foundSteps = picked.steps.map { spans(folded, $0, .step) }
        let card = folded.cards()
        var counts = Array(repeating: 0, count: (card.last ?? 0) + 1)
        for found in foundIngredients + foundSteps {
            for c in Set(found.map { card[$0.line] }) { counts[c] += 1 }
        }
        // The first card with the most, as Kotlin's maxByOrNull.
        var recipe = 0
        for c in counts.indices where counts[c] > counts[recipe] { recipe = c }
        func kept(_ found: [Span]) -> String? { found.first { card[$0.line] == recipe }?.text }
        let ingredients = foundIngredients.compactMap(kept)
        let steps = foundSteps.compactMap(kept)
        if ingredients.isEmpty && steps.isEmpty { return nil }
        func other(_ s: String?) -> String? { s.flatMap { find(folded, $0, .other) } }
        return PageSelection(
            name: name, ingredients: ingredients, steps: steps, yield: other(picked.yield),
            prepTime: other(picked.prepTime), cookTime: other(picked.cookTime), totalTime: other(picked.totalTime)
        )
    }

    /// The page's own text for `picked`, if it is on `page` as a whole span of this `kind`.
    static func find(_ page: String, _ picked: String, kind: Kind) -> String? { find(Folded(page), picked, kind) }

    private static func find(_ page: Folded, _ picked: String, _ kind: Kind) -> String? {
        spans(page, picked, kind).first?.text
    }

    /// Where `find` finds a pick: the page's own text, and the line it starts on.
    private struct Span { let text: String; let line: Int }

    /// Every place `picked` is on `page` as a whole span of this `kind`, in page order.
    private static func spans(_ page: Folded, _ picked: String, _ kind: Kind) -> [Span] {
        let needle = Folded(picked).text
        if needle.isEmpty { return [] }
        if kind != .other && !needle.contains(where: isLetter) { return [] }
        let text = page.text
        var found: [Span] = []
        var at = 0
        while at + needle.count <= text.count {
            if text[at] == needle[0], Array(text[at..<(at + needle.count)]) == needle,
               startsCleanly(page, at, needle, kind), endsCleanly(page, at + needle.count, needle) {
                found.append(Span(text: page.original(at, at + needle.count), line: page.lines[at]))
            }
            at += 1
        }
        return found
    }

    private static func startsCleanly(_ page: Folded, _ at: Int, _ needle: [UInt16], _ kind: Kind) -> Bool {
        let text = page.text
        if kind == .ingredient {
            var i = at - 1
            while i >= 0 && !page.lineBreak[i] {
                if isLetterOrDigit(text[i]) { return false }
                i -= 1
            }
            return true
        }
        if at == 0 || page.lineBreak[at - 1] { return true }
        if isLetterOrDigit(needle[0]) && isLetterOrDigit(text[at - 1]) { return false }
        if !isDigit(needle[0]) { return true }
        var i = at - 1
        while i >= 0 && text[i] == space && !page.lineBreak[i] { i -= 1 }
        if i < 0 || page.lineBreak[i] { return true }
        let c = text[i]
        return !(isDigit(c) || c == slash || c == hyphen || ((c == dot || c == comma) && i > 0 && isDigit(text[i - 1])))
    }

    private static func endsCleanly(_ page: Folded, _ end: Int, _ needle: [UInt16]) -> Bool {
        let text = page.text
        if end >= text.count || page.lineBreak[end] { return true }
        let last = needle[needle.count - 1]
        if isLetterOrDigit(last) && isLetterOrDigit(text[end]) { return false }
        if !isDigit(last) { return true }
        var i = end
        while i < text.count && text[i] == space && !page.lineBreak[i] { i += 1 }
        if i >= text.count || page.lineBreak[i] { return true }
        let c = text[i]
        return !(isDigit(c) || c == slash || c == hyphen || ((c == dot || c == comma) && i + 1 < text.count && isDigit(text[i + 1])))
    }
}
