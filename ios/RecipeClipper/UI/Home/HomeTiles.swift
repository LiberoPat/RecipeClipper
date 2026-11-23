import SwiftUI

/// "Add a recipe" (#286): Paste a link (the Recipes screen's alert), Scan a recipe (#226; nil
/// `onScan` while the photoText flag is off, leaving two tiles) and Type a recipe (#29), as equal
/// tiles, then a muted line saying a link can be shared from any app. The tiles stack at the
/// accessibility sizes, where three across would leave each a word wide.
struct AddRecipeBlock: View {
    let onPasteLink: () -> Void
    let onScan: (([Data]) -> Void)?
    let onTypeRecipe: () -> Void
    /// "Ask an AI" (#351): nil (the askAi flag off) leaves the tile out.
    var onAskAi: (() -> Void)? = nil
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
            // Ask an AI (#351): a prompt for any chat app, whose answer comes back as shared text.
            if let onAskAi {
                Button(action: onAskAi) {
                    HomeTile(icon: "sparkles", name: Strings.askAi, hint: Strings.homeAskAiHint, wide: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.askAi")
            }
            Text(Strings.homeShareHint)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
                .padding(.top, 2)
        }
    }
}

/// One way in: an icon, its name and a short muted line; `filled` is the basil one.
private struct HomeTile: View {
    let icon: String
    let name: String
    let hint: String
    var filled = false
    /// Across the row: the icon, then the name over its hint.
    var wide = false

    var body: some View {
        Group {
            if wide {
                HStack(alignment: .center, spacing: 12) { iconBox; words }
            } else {
                VStack(alignment: .leading, spacing: 6) { iconBox; words }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(filled ? Palette.primary : Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.card))
        .contentShape(RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .combine)
    }

    private var iconBox: some View {
        Image(systemName: icon)
            .font(.system(size: 17, weight: .medium))
            .foregroundStyle(filled ? Palette.onPrimary : Palette.accentText)
            .frame(width: 36, height: 36)
            .background(
                filled ? Palette.onPrimary.opacity(0.18) : Palette.accentText.opacity(0.12),
                in: RoundedRectangle(cornerRadius: Radius.small)
            )
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var words: some View {
        VStack(alignment: .leading, spacing: wide ? 2 : 6) {
            Text(name)
                .textStyle(Typography.titleSmall)
                .foregroundStyle(filled ? Palette.onPrimary : Palette.onBackground)
            Text(hint)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(filled ? Palette.onPrimary.opacity(0.85) : Palette.muted)
        }
    }
}
