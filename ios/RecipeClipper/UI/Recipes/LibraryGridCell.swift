import SwiftUI

/// One recipe of the Recipes photo grid (#380): the photo, square, with the bookmark on its corner
/// (the same save control as a row's: filled for a recipe in a list, outlined to open the
/// save-to-list sheet), the title under it, then the site and total time as a row has them. With
/// the pantry chip on (#378), "You have 6 of 8" under that. No swipe to delete here: a grid has no
/// row to swipe, so Delete is in the long-press menu (#376, put on the cell by the screen) and a
/// selection's (#377). While `selecting` a checkbox rides the photo's corner and a tap (the
/// caller's `onClick`) toggles it, the bookmark button is left out, as in a row. Android's
/// `LibraryGridCell`.
struct LibraryGridCell: View {
    let recipe: RecipeSummary
    let onClick: () -> Void
    /// One cookbook (#345): an outlined bookmark on a recipe in no list, opening the save-to-list sheet.
    var onSaveToList: (() -> Void)? = nil
    var pantry: PantryCoverage? = nil
    /// Selecting several (#377). The checkbox is always in the cell, clear when not selecting, so
    /// the cell's structure never changes with the mode (#203, #216).
    var selecting = false
    var selected = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Button(action: onClick) {
                VStack(alignment: .leading, spacing: 2) {
                    photo
                        .padding(.bottom, 6)
                    Text(recipe.title)
                        .textStyle(Typography.bodyLargeBold)
                        .foregroundStyle(Palette.onBackground)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    let detail = [
                        Strings.sourceCredit(recipe.sourceDomain, clipped: recipe.isClipped)
                            ?? (recipe.isFromChat ? Strings.recipesViewChat : nil),
                        recipe.totalTime
                    ].compactMap { $0 }.joined(separator: " · ")
                    if !detail.isEmpty {
                        Text(detail)
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.muted)
                            .lineLimit(1)
                    }
                    if let pantry { PantryLine(pantry: pantry, recipeId: recipe.id) }
                }
                .frame(maxWidth: .infinity, alignment: .topLeading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selecting && selected ? [.isSelected] : [])
            .accessibilityIdentifier("recipes.cell.\(recipe.id)")

            CheckboxGlyph(checked: selected)
                .padding(8)
                .background(Circle().fill(Palette.background.opacity(0.9)).padding(2))
                .padding(6)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .opacity(selecting ? 1 : 0)
                .allowsHitTesting(false)
                .accessibilityIdentifier("recipes.check.\(recipe.id)")

            if recipe.isSaved {
                badge(Image(systemName: "bookmark.fill").foregroundStyle(Palette.accentText))
                    .accessibilityLabel(Strings.inAList)
            } else if let onSaveToList, !selecting {
                Button(action: onSaveToList) {
                    badge(Image(systemName: "bookmark").foregroundStyle(Palette.muted))
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Strings.saveToList)
                .accessibilityIdentifier("recipes.saveToList.\(recipe.id)")
            }
        }
    }

    /// The photo, square, or the recipe's designed image when it has none or the photo fails
    /// (#403; the blank tile with `noPhotoImage` off).
    private var photo: some View {
        let shape = RoundedRectangle(cornerRadius: 10)
        return Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { RecipeImage(url: recipe.imageUrl, recipe: recipe.noPhoto) }
            .clipShape(shape)
            .accessibilityHidden(true)
    }

    /// A round, ground-coloured disc for a glyph that sits on a photo, so it reads on any picture.
    private func badge(_ glyph: some View) -> some View {
        glyph
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .font(.system(size: 15))
            .frame(width: 32, height: 32)
            .background(Circle().fill(Palette.background.opacity(0.9)))
            .padding(6)
    }
}
