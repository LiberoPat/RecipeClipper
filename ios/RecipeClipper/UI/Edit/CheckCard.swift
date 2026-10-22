import SwiftUI

/// One line to check (#286), as a card: the strip of the photo it was read from (none when the
/// reader didn't say where), the line as a field, which edits it in its box too, then why it is
/// listed: a note for an amount that only looks large (#281), or the fraction chips (#245).
/// Android's `CheckCard`.
struct CheckCard: View {
    let index: Int
    let check: LineToCheck
    let fractions: [String]
    let onLine: (Int, String) -> Void
    let onFraction: (_ flag: LineFlag, _ fraction: String) -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let strip = check.strip {
                StripView(strip: strip)
                    .accessibilityIdentifier("edit.strip.\(index)")
                    .padding(.bottom, 2)
            }
            // No label, as on Android: the strip above says where the line is from.
            TextField("", text: Binding(get: { check.flag.raw }, set: { onLine(index, $0) }))
            .textStyle(Typography.bodyLarge)
            .foregroundStyle(Palette.onBackground)
            .focused($focused)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.background))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(focused ? Palette.primary : Palette.outline, lineWidth: focused ? 2 : 1)
            )
            .accessibilityIdentifier("edit.checkLine.\(index)")
            if check.flag.largeOnly {
                lineNote(Strings.photoCheckLarge)
            }
            if check.flag.chips {
                lineNote(Strings.photoFractionPrompt)
                // One row, else two of three, else one under another (large text): never a
                // grid narrower than its chips, which drew them over each other.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 6) { fractionButtons(fractions) }
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) { fractionButtons(Array(fractions.prefix(3))) }
                        HStack(spacing: 6) { fractionButtons(Array(fractions.dropFirst(3))) }
                    }
                    VStack(alignment: .leading, spacing: 6) { fractionButtons(fractions) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Palette.surfaceContainer))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("edit.check.\(index)")
    }

    /// A line whose amount holds a fraction glyph's look-alike (#245), or glyphs run together
    /// (#276, "½⅓"): the fractions as one-tap replacements, since "%" can't say whether it was ½,
    /// ¼ or ¾. The cook can still type instead.
    private func fractionButtons(_ shown: [String]) -> some View {
        ForEach(shown, id: \.self) { fraction in
            Button { onFraction(check.flag, fraction) } label: {
                Text(fraction)
                    .textStyle(Typography.titleMedium)
                    .foregroundStyle(Palette.onBackground)
                    .frame(minWidth: 44, minHeight: 44)
                    .padding(.horizontal, 2)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Palette.background))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.outline, lineWidth: 1))
                    .contentShape(RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Strings.photoFractionChip(fraction, check.flag.shown))
            .accessibilityIdentifier("edit.fraction.\(index).\(fraction)")
        }
    }

    /// A muted line under a line to check: why it's listed (#281), or what its chips are for (#245).
    private func lineNote(_ text: String) -> some View {
        Text(text)
            .textStyle(Typography.bodySmall)
            .foregroundStyle(Palette.muted)
    }
}
