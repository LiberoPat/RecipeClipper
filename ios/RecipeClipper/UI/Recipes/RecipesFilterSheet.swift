import SwiftUI

// Recipes' views folded into the filter sheet (#454, `recipesFilters`, over design B): the Lists pill left of
// search, the sheet's two groups (the recipe; where it is and where it came from) and the pills of the filters
// on. What each filter selects is `RecipeFilterChoices`' (Data/Model), the view screens' and #378's own rules.
// Android's `RecipesFilterSheet.kt`.

/// Lists, left of search (#454): the icon, "Lists" and how many, in the accent tone. It opens the Lists screen,
/// Back returns. It takes the width its words need and never clips; search takes the rest.
struct ListsPill: View {
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "list.bullet")
                    .font(.system(size: 15, weight: .semibold))
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                Text(Strings.navLists)
                    .textStyle(Typography.labelLarge)
                Text("\(count)")
                    .textStyle(Typography.labelLarge)
                    .opacity(0.75)
            }
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(Palette.onAccentTone)
            .padding(.leading, 12)
            .padding(.trailing, 14)
            .frame(minHeight: 52)
            .background(Palette.accentTone, in: RoundedRectangle(cornerRadius: Radius.field))
            .contentShape(RoundedRectangle(cornerRadius: Radius.field))
        }
        .buttonStyle(.plain)
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(Strings.navLists), \(count)")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("recipes.listsPill")
    }
}

/// The pills of the filters on (#454), each removed by a tap on it, then Clear; with the pantry on, its line.
struct ChoicePills: View {
    let pills: [RecipeFilterPill]
    let onRemove: (RecipeFilterPill) -> Void
    let onClear: () -> Void

    var body: some View {
        if !pills.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                PillFlow(spacing: 8, lineSpacing: 0) {
                    ForEach(pills) { pill($0) }
                    Button(Strings.clear, action: onClear)
                        .buttonStyle(TextActionStyle())
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("recipes.filterClear")
                }
                if pills.contains(.usesPantry) {
                    Text(Strings.recipesQuickPantryNote)
                        .textStyle(Typography.bodySmall)
                        .foregroundStyle(Palette.muted)
                        .padding(.leading, 2)
                        .padding(.bottom, 4)
                        .accessibilityIdentifier("recipes.quick.pantryNote")
                }
            }
            .accessibilityElement(children: .contain)
        }
    }

    private func pill(_ pill: RecipeFilterPill) -> some View {
        let name = Self.name(pill)
        return Button { onRemove(pill) } label: {
            HStack(spacing: 6) {
                Text(name).textStyle(ActiveFilterPills.pillStyle)
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            }
            .foregroundStyle(Palette.onAccentTone)
            .padding(.leading, 12)
            .padding(.trailing, 10)
            .frame(minHeight: 32)
            .background(Palette.accentTone, in: Capsule())
            // The 44pt touch target is the outer frame; the pill is the shape inside it.
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Strings.removeFilter(name))
        .accessibilityIdentifier("recipes.filterPill.\(pill.rawValue)")
    }

    /// A pill's words: the view's or #378's own name, so a filter reads the same in the sheet, the pill and before.
    static func name(_ pill: RecipeFilterPill) -> String {
        switch pill {
        case .thirtyOrLess: Strings.recipesQuickThirtyOrLess
        case .cooked: Strings.recipesViewCooked
        case .notCooked: Strings.recipesQuickNotCooked
        case .usesPantry: Strings.recipesQuickPantry
        case .notInAList: Strings.recipesFilterNotInList
        case .typed: RecipeFilterPills.name(.typed)
        case .chat: RecipeFilterPills.name(.chat)
        }
    }
}

/// The filter sheet with the views folded in (#454): "The recipe" (30 min or less, Cooked: Any / Cooked / Not
/// yet, Uses what's in my pantry) and "Where it is, where it came from" (Not in a list, Came from: Anywhere /
/// Typed by you / From a chat), and Clear. Each control answers at once; the list behind follows.
struct RecipeFiltersSheet: View {
    let choices: RecipeFilterChoices
    let onChange: (RecipeFilterChoices) -> Void
    let onClear: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(Strings.recipesFilterTitle)
                        .textStyle(Typography.titleLarge)
                        .foregroundStyle(Palette.onBackground)
                        .accessibilityAddTraits(.isHeader)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(Strings.clear, action: onClear)
                        .buttonStyle(TextActionStyle())
                        .disabled(choices.isEmpty)
                        .accessibilityIdentifier("recipes.filterSheet.clear")
                }
                .padding(.bottom, 4)
                group(Strings.recipesFilterGroupRecipe)
                SwitchRow(
                    title: Strings.recipesQuickThirtyOrLess, description: Strings.recipesQuickThirtyOrLessHint,
                    isOn: binding(\.thirtyOrLess)
                )
                .accessibilityIdentifier("recipes.filterSwitch.THIRTY_OR_LESS")
                Hairline()
                ChoiceRow(
                    title: Strings.recipesViewCooked, options: CookedChoice.allCases, selected: choices.cooked,
                    label: Self.label, id: "recipes.filterCooked",
                    onSelect: { onChange(with(\.cooked, $0)) }
                )
                Hairline()
                SwitchRow(
                    title: Strings.recipesQuickPantry, description: Strings.recipesQuickPantryHint,
                    isOn: binding(\.usesPantry)
                )
                .accessibilityIdentifier("recipes.filterSwitch.USES_PANTRY")
                group(Strings.recipesFilterGroupWhere)
                SwitchRow(
                    title: Strings.recipesFilterNotInList, description: Strings.recipesFilterNotInListHint,
                    isOn: binding(\.notInAList)
                )
                .accessibilityIdentifier("recipes.filterSwitch.NOT_IN_A_LIST")
                Hairline()
                ChoiceRow(
                    title: Strings.recipesFilterCameFrom, options: OriginChoice.allCases, selected: choices.origin,
                    label: Self.label, id: "recipes.filterOrigin",
                    onSelect: { onChange(with(\.origin, $0)) }
                )
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 16)
        }
        .background(Palette.background)
        .accessibilityIdentifier("recipes.filterSheet")
    }

    private func group(_ title: String) -> some View {
        SmallCapsLabel(title)
            .padding(.top, 18)
            .padding(.bottom, 2)
    }

    private func with<T>(_ key: WritableKeyPath<RecipeFilterChoices, T>, _ value: T) -> RecipeFilterChoices {
        var next = choices
        next[keyPath: key] = value
        return next
    }

    private func binding(_ key: WritableKeyPath<RecipeFilterChoices, Bool>) -> Binding<Bool> {
        Binding(get: { choices[keyPath: key] }, set: { onChange(with(key, $0)) })
    }

    static func label(_ choice: CookedChoice) -> String {
        switch choice {
        case .any: Strings.recipesFilterCookedAny
        case .cooked: Strings.recipesViewCooked
        case .notYet: Strings.recipesFilterNotYet
        }
    }

    static func label(_ choice: OriginChoice) -> String {
        switch choice {
        case .anywhere: Strings.recipesFilterAnywhere
        case .typed: RecipeFilterPills.name(.typed)
        case .chat: RecipeFilterPills.name(.chat)
        }
    }
}

/// One exclusive choice in the sheet: its title, then the options as segments.
private struct ChoiceRow<Option: Identifiable & Hashable & RawRepresentable>: View where Option.RawValue == String {
    let title: String
    let options: [Option]
    let selected: Option
    let label: (Option) -> String
    let id: String
    let onSelect: (Option) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.onBackground)
            ChoiceSegments(options: options, selected: selected, label: label, id: id, onSelect: onSelect)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A segmented choice (the Pantry edit sheet's control, drawn as the mockup's track): the options side by side,
/// the chosen one a raised pill. When their words don't fit one line side by side (German, Dynamic Type), they
/// stack, each the full width, so nothing is cut. Each is a button with the selected trait for VoiceOver.
private struct ChoiceSegments<Option: Identifiable & Hashable & RawRepresentable>: View where Option.RawValue == String {
    let options: [Option]
    let selected: Option
    let label: (Option) -> String
    let id: String
    let onSelect: (Option) -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 0) { segments(oneLine: true) }
            VStack(spacing: 0) { segments(oneLine: false) }
        }
        .padding(3)
        .background(Palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Radius.field))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }

    @ViewBuilder
    private func segments(oneLine: Bool) -> some View {
        ForEach(options) { option in
            let on = option == selected
            Button { onSelect(option) } label: {
                Text(label(option))
                    .textStyle(Typography.labelLarge)
                    .foregroundStyle(on ? Palette.onBackground : Palette.muted)
                    .multilineTextAlignment(.center)
                    .lineLimit(oneLine ? 1 : nil)
                    .fixedSize(horizontal: oneLine, vertical: true)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(on ? Palette.background : Color.clear, in: RoundedRectangle(cornerRadius: Radius.small))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(on ? .isSelected : [])
            .accessibilityIdentifier("\(id).\(option.rawValue)")
        }
    }
}
