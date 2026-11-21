import SwiftUI

/// The source's tips (#432), below the method (Android's `TipsSection`): a quiet "Tips" heading
/// (muted, like Versions), then each tip as written, unnumbered, so they never read as more steps.
/// Cook mode never shows them.
struct TipsSection: View {
    let tips: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Strings.headingTips)
                .textStyle(Typography.titleSmall)
                .foregroundStyle(Palette.muted)
                .accessibilityAddTraits(.isHeader)
            ForEach(Array(tips.enumerated()), id: \.offset) { _, tip in
                Text(tip)
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            }
        }
        .accessibilityIdentifier("recipe.tips")
    }
}
