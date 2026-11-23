import SwiftUI

// Recipes' views folded into the filter sheet (#454, `recipesFilters`, over design B): the Lists pill in the
// title row, the sheet (the switches, then the two choices) and the pills of the filters
// on. What each filter selects is `RecipeFilterChoices`' (Data/Model), the view screens' and #378's own rules.
// Android's `RecipesFilterSheet.kt`.

/// Lists, in the title row and the pinned bar (#454, the owner's move, 2026-11-23): the icon, "Lists" and how many,
/// in the accent tone. It opens the Lists screen, Back returns. `compact` drops the word and keeps the icon and the
/// count, for a title row with no room for it (`TitleRowFit` decides); the spoken label is the same either way.
struct ListsPill: View {
    let count: Int
    let action: () -> Void
    var compact = false
    var id = "recipes.listsPill"

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "list.bullet")
                    .font(.system(size: 15, weight: .semibold))
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                if !compact {
                    Text(Strings.navLists)
                        .textStyle(Typography.labelLarge)
                }
                Text("\(count)")
                    .textStyle(Typography.labelLarge)
                    .opacity(compact ? 1 : 0.75)
            }
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(Palette.onAccentTone)
            .padding(.leading, 12)
            .padding(.trailing, 14)
            .frame(minHeight: 36)
            .background(Palette.accentTone, in: Capsule())
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(Strings.navLists), \(count)")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(id)
    }
}

/// A title row: the title on the left, the trailing buttons on the right and, between them, the Lists pill. The
/// title keeps its whole word. The pill shows "Lists" and its count when the title, the pill and the buttons fit
/// side by side; else only its icon and count; and if even that does not fit (a very large Dynamic Type size on a
/// narrow phone), it moves under the title, right-aligned, so nothing is pushed off-screen or clipped.
/// `ViewThatFits` takes the first of the three that fits. Android's `TitleRowLayout`.
struct TitleRowFit<Title: View, Trailing: View>: View {
    let listCount: Int?
    let onOpenLists: () -> Void
    let listsId: String
    @ViewBuilder let title: () -> Title
    @ViewBuilder let trailing: () -> Trailing

    private func row(_ pill: ListsPill?) -> some View {
        HStack(spacing: 0) {
            title()
            Spacer(minLength: 8)
            if let pill { pill }
            trailing()
        }
    }

    var body: some View {
        if let listCount {
            ViewThatFits(in: .horizontal) {
                row(ListsPill(count: listCount, action: onOpenLists, id: listsId))
                row(ListsPill(count: listCount, action: onOpenLists, compact: true, id: listsId))
                VStack(alignment: .trailing, spacing: 0) {
                    row(nil)
                    ListsPill(count: listCount, action: onOpenLists, compact: true, id: listsId)
                }
            }
        } else {
            row(nil)
        }
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

/// The filter sheet with the views folded in (#454), grouped by kind of control (the owner's regrouping,
/// 2026-11-23): the switches first (30 min or less, Uses what's in my pantry, Not in a list), then the two
/// segmented choices (Cooked: Any / Cooked / Not yet; Came from: Anywhere / Typed by you / From a chat), and
/// Clear. Each control answers at once; the list behind follows.
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
                SwitchRow(
                    title: Strings.recipesQuickThirtyOrLess, description: Strings.recipesQuickThirtyOrLessHint,
                    isOn: binding(\.thirtyOrLess)
                )
                .accessibilityIdentifier("recipes.filterSwitch.THIRTY_OR_LESS")
                Hairline()
                SwitchRow(
                    title: Strings.recipesQuickPantry, description: Strings.recipesQuickPantryHint,
                    isOn: binding(\.usesPantry)
                )
                .accessibilityIdentifier("recipes.filterSwitch.USES_PANTRY")
                Hairline()
                SwitchRow(
                    title: Strings.recipesFilterNotInList, description: Strings.recipesFilterNotInListHint,
                    isOn: binding(\.notInAList)
                )
                .accessibilityIdentifier("recipes.filterSwitch.NOT_IN_A_LIST")
                Spacer().frame(height: 20)
                ChoiceRow(
                    title: Strings.recipesViewCooked, options: CookedChoice.allCases, selected: choices.cooked,
                    label: Self.label, id: "recipes.filterCooked",
                    onSelect: { onChange(with(\.cooked, $0)) }
                )
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
