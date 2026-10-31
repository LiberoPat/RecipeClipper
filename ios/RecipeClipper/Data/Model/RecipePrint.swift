import Foundation

/// A recipe as printed (#318): the card the print dialog or a cookbook PDF draws, as an ordered
/// list of `Block`s. Pure, like `RecipeShareText`, and built from the same inputs: the lines
/// already rendered for the screen (scaled, converted), so nothing here scales, converts or
/// re-parses. Ticked lines print plain. The screen only measures and draws the blocks; where the
/// pages break is `paginate`, and a cookbook's page numbers are `startPages`, so both apps break
/// a recipe at the same block. A line-for-line port of Android's `RecipePrint`.
enum RecipePrint {

    /// One thing on the page, top to bottom.
    enum Block: Equatable {
        /// The recipe's photo, when it is cached on the phone; the screen loads the URL.
        case photo(String)
        case title(String)
        /// The site it came from ("smittenkitchen.com"), quietly under the title.
        case credit(String)
        /// The serves line, then the times line (`RecipeShareText.metaLines`).
        case meta(String)
        case section(String)
        /// An ingredient group heading (`IngredientHeading`): no bullet, never scaled.
        case groupHeading(String)
        case ingredient(String)
        case step(Int, String)
        case note(String)
    }

    /// The section headings, from the screen's strings; `share` gives the serves and times words.
    struct Labels {
        var share: RecipeShareText.Labels
        var ingredients: String
        var instructions: String
        var notes: String

        static let english = Labels(share: .english, ingredients: "Ingredients", instructions: "Instructions", notes: "Notes")
    }

    /// `recipe`'s card. `ingredients` and `instructions` are the lines as shown; `note` is the
    /// cook's own (printed on a single card; a cookbook leaves it out, as Send as file does);
    /// `photo` says whether to place the photo, which the screen draws only if it is cached.
    static func blocks(
        recipe: Recipe,
        servings: ServingsScale?,
        ingredients: [String],
        instructions: [String],
        sourceDomain: String?,
        note: String?,
        labels: Labels = .english,
        photo: Bool = true
    ) -> [Block] {
        var blocks: [Block] = []
        if photo, let image = recipe.image, !image.kTrimmed.isEmpty { blocks.append(.photo(image)) }
        blocks.append(.title(recipe.name))
        if let sourceDomain, !sourceDomain.kTrimmed.isEmpty { blocks.append(.credit(sourceDomain)) }
        blocks += RecipeShareText.metaLines(recipe, servings, labels.share).map { .meta($0) }
        let lines = ingredients.filter { !$0.kTrimmed.isEmpty }
        if !lines.isEmpty {
            blocks.append(.section(labels.ingredients))
            blocks += lines.map { IngredientHeading.isHeading($0) ? .groupHeading($0.kTrimmed) : .ingredient($0) }
        }
        let steps = instructions.filter { !$0.kTrimmed.isEmpty }
        if !steps.isEmpty {
            blocks.append(.section(labels.instructions))
            blocks += steps.enumerated().map { .step($0.offset + 1, $0.element) }
        }
        if let note = note?.kTrimmed, !note.isEmpty {
            blocks.append(.section(labels.notes))
            blocks.append(.note(note))
        }
        return blocks
    }

    /// Whether `block` must not end a page: the title and what sits under it, and headings, stay
    /// with the first line after them.
    static func keepsWithNext(_ block: Block) -> Bool {
        switch block {
        case .photo, .title, .credit, .meta, .section, .groupHeading: return true
        case .ingredient, .step, .note: return false
        }
    }

    /// The page (from 0) each block goes on, given each block's `heights` (its space above
    /// included) and `keep` (`keepsWithNext`) in the same order, on pages `pageHeight` tall. A
    /// block that doesn't fit starts a new page, and so does a run of kept blocks with the line
    /// after it, unless that run is taller than a page by itself. A block taller than a page gets
    /// a page of its own.
    static func paginate(_ heights: [Double], keep: [Bool], pageHeight: Double) -> [Int] {
        var pages: [Int] = []
        var page = 0
        var used = 0.0
        for i in heights.indices {
            var run = heights[i]
            var j = i
            while j < heights.count - 1 && (j < keep.count ? keep[j] : false) {
                j += 1
                run += heights[j]
            }
            let needed = run <= pageHeight ? run : heights[i]
            if used > 0 && used + needed > pageHeight {
                page += 1
                used = 0
            }
            pages.append(page)
            used += heights[i]
        }
        return pages
    }

    /// A cookbook's page numbers (from 1): the title page is 1, then `contentsPages` pages of
    /// contents, then each recipe from a new page, taking `pageCounts` pages each in order.
    /// Returns the first page of each recipe, for the contents.
    static func startPages(_ pageCounts: [Int], contentsPages: Int) -> [Int] {
        var next = 1 + contentsPages + 1
        return pageCounts.map { count in
            defer { next += max(count, 1) }
            return next
        }
    }
}
