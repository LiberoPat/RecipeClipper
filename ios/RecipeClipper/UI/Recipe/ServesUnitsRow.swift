import SwiftUI

/// Servings and units, always in view: `Serves − 6 +` on the left and the unit choice on the
/// right. Servings is per-recipe; the unit choice is the user's global default, and the menu
/// says so. The menu is exclusive-choice only: "Also convert liquids" lives in Settings.
struct ServesUnitsRow: View {
    let servings: ServingsScale?
    let yieldText: String?
    /// The recipe's words (#14), for whether the yield counts servings or things made.
    let words: LanguageWords?
    let unitSystem: UnitSystem
    let onServingsChange: (Int) -> Void
    let onUnitSystemChange: (UnitSystem) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Hairline()
            // One row when it fits; stacked when it doesn't (a narrow screen, a long unit
            // label, the larger text sizes), rather than pushing the unit menu off the edge.
            // At the largest sizes even `Serves − 6 +` is wider than the screen, so the last
            // resort puts the word above the stepper.
            ViewThatFits(in: .horizontal) {
                HStack {
                    serves(labelAbove: false)
                    Spacer(minLength: 8)
                    UnitsMenu(system: unitSystem, onChange: onUnitSystemChange)
                }
                VStack(alignment: .leading, spacing: 0) {
                    serves(labelAbove: false)
                    UnitsMenu(system: unitSystem, onChange: onUnitSystemChange)
                }
                VStack(alignment: .leading, spacing: 0) {
                    serves(labelAbove: true)
                    UnitsMenu(system: unitSystem, onChange: onUnitSystemChange)
                }
            }
            .padding(.vertical, 4)
            if let servings, servings.target != servings.base, let yieldText {
                Text(Strings.originalServings(Servings.bareCount(yieldText).map(Strings.servings) ?? yieldText))
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 8)
            }
            Hairline()
        }
    }

    @ViewBuilder
    private func serves(labelAbove: Bool) -> some View {
        if let servings {
            ServesStepper(servings: servings, kind: Servings.kind(yieldText, words: words), labelAbove: labelAbove, onChange: onServingsChange)
        } else {
            // No number in the yield to scale from: show what the recipe says, if anything.
            Text(yieldText ?? "")
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.onBackground)
        }
    }
}

private struct ServesStepper: View {
    let servings: ServingsScale
    /// "Makes 16" for a yield that counts things made (cookies, loaves); only the words change.
    let kind: YieldKind
    let labelAbove: Bool
    let onChange: (Int) -> Void
    @ScaledMetric(relativeTo: .headline) private var numberWidth: CGFloat = 32

    var body: some View {
        if labelAbove {
            VStack(alignment: .leading, spacing: 4) {
                label
                stepper
            }
        } else {
            HStack(spacing: 4) {
                label.padding(.trailing, 2)
                stepper
            }
        }
    }

    private var label: some View {
        Text(kind == .makes ? Strings.makes : Strings.serves)
            .textStyle(Typography.bodyLarge)
            .foregroundStyle(Palette.onBackground)
            .lineLimit(1)
            .fixedSize()
    }

    private var stepper: some View {
        HStack(spacing: 4) {
            StepButton(symbol: "−", label: kind == .makes ? Strings.decreaseAmount : Strings.decreaseServings, enabled: servings.target > 1) {
                onChange(servings.target - 1)
            }
            Text("\(servings.target)")
                .textStyle(Typography.titleMedium)
                .monospacedDigit()
                .foregroundStyle(Palette.onBackground)
                .fixedSize()
                .frame(minWidth: numberWidth)
            StepButton(symbol: "+", label: kind == .makes ? Strings.increaseAmount : Strings.increaseServings, enabled: servings.target < Servings.max) {
                onChange(servings.target + 1)
            }
        }
    }
}

/// A tonal circle (Material's FilledTonalIconButton).
private struct StepButton: View {
    let symbol: String
    let label: String
    let enabled: Bool
    let action: () -> Void
    /// Grows with the glyph inside it, capped: past ~60pt the circles crowd the number out.
    @ScaledMetric(relativeTo: .title3) private var scaledSize: CGFloat = 40
    private var size: CGFloat { min(scaledSize, 60) }

    var body: some View {
        Button(action: action) {
            Text(symbol)
                .textStyle(Typography.titleLarge)
                .foregroundStyle(Palette.onBackground)
                .frame(width: size, height: size)
                .background(Circle().fill(Palette.surfaceContainer))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.38)
        .accessibilityLabel(label)
    }
}

/// The current unit choice as tappable accent text; tapping opens the three exclusive options.
private struct UnitsMenu: View {
    let system: UnitSystem
    let onChange: (UnitSystem) -> Void

    var body: some View {
        Menu {
            Section(Strings.unitsMenuHeader) {
                ForEach(UnitSystem.allCases, id: \.self) { option in
                    Button { onChange(option) } label: {
                        Text(Strings.unitLabel(option))
                        Text(Strings.unitDescription(option))
                        if option == system { Image(systemName: "checkmark") }
                    }
                }
            }
        } label: {
            HStack(spacing: 0) {
                Text(Strings.unitLabel(system)).textStyle(Typography.bodyLargeBold)
                Text(" ▾").textStyle(Typography.bodyLarge)
            }
            .foregroundStyle(Palette.accentText)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 4)
            .padding(.vertical, 12)
        }
        .accessibilityLabel(Strings.changeUnits)
        .accessibilityValue(Strings.unitLabel(system))
    }
}
