import SwiftUI

/// A row of the Recipes library (#286, the tidy list): photo, title, the site and total time
/// under it, and a filled bookmark, not the word "Saved", when the recipe is in any list. The
/// site is credited as the reading view credits it. Home and List detail keep `RecipeRow`.
struct LibraryRow: View {
    let recipe: RecipeSummary
    let onClick: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: onClick) {
            // At the accessibility sizes a thumbnail beside the text would leave it a column
            // a few letters wide, so the thumbnail goes above and the title may run longer.
            let stacked = dynamicTypeSize.isAccessibilitySize
            let layout = stacked
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
                : AnyLayout(HStackLayout(spacing: 14))
            layout {
                RecipeThumbnail(url: recipe.imageUrl)
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(recipe.title)
                            .textStyle(Typography.bodyLargeBold)
                            .foregroundStyle(Palette.onBackground)
                            .lineLimit(stacked ? 5 : 2)
                            .multilineTextAlignment(.leading)
                        details(stacked: stacked)
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if recipe.isSaved {
                        Image(systemName: "bookmark.fill")
                            .foregroundStyle(Palette.accentText)
                            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                            .accessibilityLabel(Strings.inAList)
                    }
                }
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// "site · 1h 15m" on one line: a long site is cut short, never the time after it. At the
    /// accessibility sizes it wraps instead.
    @ViewBuilder
    private func details(stacked: Bool) -> some View {
        let credit = Strings.sourceCredit(recipe.sourceDomain, clipped: recipe.isClipped)
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
