import SwiftUI

/// A recipe in a list: thumbnail, title, total time and when it was last viewed, plus a
/// "Saved" tag when it is in any list.
struct RecipeRow: View {
    let recipe: RecipeSummary
    let now: Int64
    let onClick: () -> Void
    /// Selecting several (#377): a checkbox leads the row, always in it and zero wide when not
    /// selecting (#203, #216), and a tap (the caller's `onClick`) toggles it.
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
                VStack(alignment: .leading, spacing: 2) {
                    Text(recipe.title)
                        .textStyle(Typography.bodyLargeBold)
                        .foregroundStyle(Palette.onBackground)
                        .lineLimit(stacked ? 5 : 2)
                        .multilineTextAlignment(.leading)
                    Text(details)
                        .textStyle(Typography.bodySmall)
                        .foregroundStyle(Palette.muted)
                    if recipe.isSaved {
                        Text(Strings.tagSaved)
                            .textStyle(Typography.labelSmall)
                            .foregroundStyle(Palette.accentText)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selecting && selected ? [.isSelected] : [])
    }

    private var details: String {
        [recipe.isClipped ? Strings.clippedByYou : nil, recipe.isFromChat ? Strings.recipesViewChat : nil, recipe.totalTime, Strings.elapsed(TimeAgo.since(recipe.lastViewedAt, now: now))]
            .compactMap { $0 }
            .joined(separator: "  ·  ")
    }
}

/// A row's photo, or the recipe's designed image when it has none or the photo fails to load
/// (#403: the title's first letter at 64 pt; a plain tile with `noPhotoImage` off). Shared with
/// the Recipes library and Home's Recently viewed (#286), which asks for a larger `base`.
struct RecipeThumbnail: View {
    let url: String?
    let recipe: NoPhotoSource
    var base: CGFloat = 64
    /// Grows a little with the text, capped at half again: it is decoration, the title is the content.
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    private var size: CGFloat { min(base * scale, base * 1.5) }

    var body: some View {
        RecipeImage(url: url, recipe: recipe)
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .accessibilityHidden(true)
    }
}

/// The current wall-clock time in the same epoch-millisecond units the data layer uses.
/// Read once per screen appearance, like Android's `remember { System.currentTimeMillis() }`.
func currentMillis() -> Int64 { SystemClock().now() }
