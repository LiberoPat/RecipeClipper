import SwiftUI

/// The quick filters under search (#378): plain text pills like the old filter pills, each its own
/// on/off switch (selected for VoiceOver when on; they combine, where the pills' selection is exclusive).
/// They pick recipes, never ingredients, so the reading view's no-chips rule is untouched. With the
/// pantry chip on, one muted line says what it keeps, so the rule is never a hidden verdict.
/// Android's `QuickFilterChips`.
struct QuickFilterChips: View {
    let offered: [RecipeQuickFilter]
    let on: Set<RecipeQuickFilter>
    let onToggle: (RecipeQuickFilter) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PillFlow(spacing: 6, lineSpacing: 0) {
                ForEach(offered) { filter in chip(filter) }
            }
            .accessibilityElement(children: .contain)
            if on.contains(.usesPantry) {
                Text(Strings.recipesQuickPantryNote)
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .padding(.leading, 2)
                    .padding(.bottom, 4)
                    .accessibilityIdentifier("recipes.quick.pantryNote")
            }
        }
    }

    private func chip(_ filter: RecipeQuickFilter) -> some View {
        let isOn = on.contains(filter)
        return Button { onToggle(filter) } label: {
            Text(Self.name(filter))
                .textStyle(Typography.labelLarge)
                .foregroundStyle(isOn ? Palette.background : Palette.onBackground)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background {
                    if isOn {
                        Capsule().fill(Palette.onBackground)
                    } else {
                        Capsule().strokeBorder(Palette.hairline, lineWidth: 1)
                    }
                }
                // The 44pt touch target is the outer frame; the pill is the shape inside it.
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .accessibilityIdentifier("recipes.quick.\(filter.rawValue)")
    }

    static func name(_ filter: RecipeQuickFilter) -> String {
        switch filter {
        case .thirtyOrLess: Strings.recipesQuickThirtyOrLess
        case .notCooked: Strings.recipesQuickNotCooked
        case .usesPantry: Strings.recipesQuickPantry
        }
    }
}

/// "You have 6 of 8": how many of the recipe's nameable ingredients the pantry has, never a verdict.
struct PantryLine: View {
    let pantry: PantryCoverage
    let recipeId: Int64

    var body: some View {
        Text(Strings.recipesPantryHave(pantry.have, of: pantry.total))
            .textStyle(Typography.bodySmall)
            .foregroundStyle(Palette.accentText)
            .lineLimit(1)
            .accessibilityIdentifier("recipes.pantryHave.\(recipeId)")
    }
}
