import SwiftUI

/// "Add a recipe" (#286): Paste a link (the Recipes screen's alert), Scan a recipe (#226; nil
/// `onScan` while the photoText flag is off, leaving two tiles) and Type a recipe (#29), as equal
/// tiles, then a muted line saying a link can be shared from any app. The tiles stack at the
/// accessibility sizes, where three across would leave each a word wide.
struct AddRecipeBlock: View {
    let onPasteLink: () -> Void
    let onScan: (([Data]) -> Void)?
    let onTypeRecipe: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SmallCapsLabel(Strings.addRecipe)
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(spacing: 8))
                : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
            layout {
                Button(action: onPasteLink) {
                    HomeTile(icon: "link", name: Strings.pasteLink, hint: Strings.homePasteLinkHint, filled: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.pasteLink")
                .tooltipAnchor(.homeLink)
                if let onScan {
                    ScanRecipeButton(onScan: onScan) {
                        HomeTile(icon: "camera", name: Strings.scanRecipe, hint: Strings.homeScanHint)
                    }
                }
                Button(action: onTypeRecipe) {
                    HomeTile(icon: "pencil", name: Strings.typeRecipe, hint: Strings.homeTypeHint)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.typeRecipe")
            }
            .fixedSize(horizontal: false, vertical: true)
            Text(Strings.homeShareHint)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
                .padding(.top, 2)
        }
    }
}

/// One way in: an icon, its name and a short muted line; `filled` is the paprika one.
private struct HomeTile: View {
    let icon: String
    let name: String
    let hint: String
    var filled = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(filled ? Palette.onPrimary : Palette.accentText)
                .frame(width: 36, height: 36)
                .background(
                    filled ? Color.white.opacity(0.18) : Palette.accentText.opacity(0.12),
                    in: RoundedRectangle(cornerRadius: 10)
                )
                .accessibilityHidden(true)
            Text(name)
                .textStyle(Typography.titleSmall)
                .foregroundStyle(filled ? Palette.onPrimary : Palette.onBackground)
            Text(hint)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(filled ? Palette.onPrimary.opacity(0.85) : Palette.muted)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(filled ? Palette.primary : Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }
}

/// Recently viewed (#286): the five before Continue cooking, as a row of photos with their names.
struct RecentlyViewedRow: View {
    let recipes: [RecipeSummary]
    let onOpenRecipe: (Int64) -> Void
    /// The thumbnail's own scaling (`RecipeThumbnail`), so a name wraps under its photo.
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    private var width: CGFloat { min(84 * scale, 126) }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 12) {
                ForEach(recipes) { recipe in
                    Button { onOpenRecipe(recipe.id) } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            RecipeThumbnail(url: recipe.imageUrl, base: 84)
                            Text(recipe.title)
                                .textStyle(Typography.labelMedium)
                                .foregroundStyle(Palette.onBackground)
                                .lineLimit(2)
                        }
                        .frame(width: width, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .accessibilityIdentifier("home.recent")
    }
}
