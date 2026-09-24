import SwiftUI

/// A recipe in a list: thumbnail, title, total time and when it was last viewed, plus a
/// "Saved" tag when it is in any list.
struct RecipeRow: View {
    let recipe: RecipeSummary
    let now: Int64
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
                Thumbnail(url: recipe.imageUrl)
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
    }

    private var details: String {
        [recipe.isClipped ? Strings.clippedByYou : nil, recipe.totalTime, Strings.elapsed(TimeAgo.since(recipe.lastViewedAt, now: now))]
            .compactMap { $0 }
            .joined(separator: "  ·  ")
    }
}

private struct Thumbnail: View {
    let url: String?
    /// Grows a little with the text, capped: it is decoration, the title is the content.
    @ScaledMetric(relativeTo: .body) private var scaledSize: CGFloat = 64
    private var size: CGFloat { min(scaledSize, 96) }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 10)
        ZStack {
            shape.fill(Palette.hairline)
            if let url, let parsed = URL(string: url) {
                CachedAsyncImage(url: parsed) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Color.clear
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .accessibilityHidden(true)
    }
}

/// The current wall-clock time in the same epoch-millisecond units the data layer uses.
/// Read once per screen appearance, like Android's `remember { System.currentTimeMillis() }`.
func currentMillis() -> Int64 { SystemClock().now() }
