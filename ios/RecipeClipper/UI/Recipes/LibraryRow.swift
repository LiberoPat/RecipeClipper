import SwiftUI

/// A row of the Recipes library (#286, the tidy list): photo, title, the site and total time
/// under it, and a filled bookmark, not the word "Saved", when the recipe is in any list. The
/// site is credited as the reading view credits it. Home and List detail keep `RecipeRow`.
struct LibraryRow: View {
    let recipe: RecipeSummary
    let onClick: () -> Void
    /// One cookbook (#345): an outlined bookmark on a recipe in no list, opening the save-to-list sheet.
    var onSaveToList: (() -> Void)? = nil
    /// With the pantry chip on (#378): how many of its ingredients the pantry has, "You have 6 of 8".
    var pantry: PantryCoverage? = nil
    /// Selecting several (#377): the row leads with a checkbox and a tap (the caller's `onClick`)
    /// toggles it. The checkbox is always in the row, zero wide when not selecting, so the row's
    /// structure never changes with the mode (#203, #216).
    var selecting = false
    var selected = false

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        HStack(spacing: 0) {
            CheckboxGlyph(checked: selected)
                .frame(width: selecting ? 36 : 0, alignment: .leading)
                .opacity(selecting ? 1 : 0)
                .clipped()
                .accessibilityIdentifier("recipes.check.\(recipe.id)")
            rowButton
            if !recipe.isSaved, let onSaveToList, !selecting {
                Button(action: onSaveToList) {
                    BookmarkGlyph(filled: false)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Strings.saveToList)
                .accessibilityIdentifier("recipes.saveToList.\(recipe.id)")
            }
        }
    }

    private var rowButton: some View {
        Button(action: onClick) {
            // At the accessibility sizes a thumbnail beside the text would leave it a column
            // a few letters wide, so the thumbnail goes above and the title may run longer.
            let stacked = dynamicTypeSize.isAccessibilitySize
            let layout = stacked
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
                : AnyLayout(HStackLayout(spacing: 14))
            layout {
                RecipeThumbnail(url: recipe.imageUrl, recipe: recipe.noPhoto)
                // No spacing: the bookmark's own slot (below) is the gap, as beside the button.
                HStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(recipe.title)
                            .textStyle(Typography.bodyLargeBold)
                            .foregroundStyle(Palette.onBackground)
                            .lineLimit(stacked ? 5 : 2)
                            .multilineTextAlignment(.leading)
                        details(stacked: stacked)
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.muted)
                        if let pantry { PantryLine(pantry: pantry, recipeId: recipe.id) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if recipe.isSaved {
                        // The same slot as the outlined button's, so saving a recipe never moves the
                        // glyph or the text beside it. Not a button: a tap opens the recipe.
                        BookmarkGlyph(filled: true)
                            .accessibilityLabel(Strings.inAList)
                    }
                }
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selecting && selected ? [.isSelected] : [])
    }

    /// "site · 1h 15m" on one line: a long site is cut short, never the time after it. At the
    /// accessibility sizes it wraps instead.
    @ViewBuilder
    private func details(stacked: Bool) -> some View {
        let credit = Strings.sourceCredit(recipe.sourceDomain, clipped: recipe.isClipped)
            ?? (recipe.isFromChat ? Strings.recipesViewChat : nil)
        let time = recipe.totalTime
        if stacked {
            let line = [credit, time].compactMap { $0 }.joined(separator: " · ")
            if !line.isEmpty { Text(line) }
        } else if credit != nil || time != nil {
            HStack(spacing: 0) {
                if let credit { Text(credit).lineLimit(1) }
                if let time {
                    Text(credit == nil ? time : " · \(time)").lineLimit(1).layoutPriority(1)
                }
            }
        }
    }
}

/// A row's bookmark, filled (the recipe is in a list) or outlined (the save-to-list button's label).
/// Both are the same slot, glyph centred, so the glyph never moves when a recipe is saved
/// (`BookmarkSlotTests`); this is the one place the slot's size is written.
struct BookmarkGlyph: View {
    /// The slot: the button's touch target.
    static let slot = CGSize(width: 44, height: 44)

    let filled: Bool

    var body: some View {
        Image(systemName: filled ? "bookmark.fill" : "bookmark")
            .foregroundStyle(filled ? Palette.accentText : Palette.muted)
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .frame(width: Self.slot.width)
            .frame(minHeight: Self.slot.height)
    }
}
