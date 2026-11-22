import SwiftUI
import UIKit

// The top of Recipes, design B (#449, `recipesTopB`, with the library as the first screen): the title row
// with the + and a ⋮ menu (Select, Settings), the views as one segmented shelf above search, the quick
// filters (#378) in a sheet behind a button at the end of the search field, and those on as pills under
// it. The filters behave exactly as the chips did; only where they live changed. Android's `RecipesTopB.kt`.

/// "Recipes", the paprika + and the ⋮ menu, last on the right: Select (nil: nothing to select) and Settings.
struct LibraryTitleRowB: View {
    let onAdd: () -> Void
    let onSelect: (() -> Void)?
    let onOpenSettings: () -> Void
    @ScaledMetric(relativeTo: .title) private var scaled: CGFloat = 20
    private var size: CGFloat { min(scaled, 30) }

    var body: some View {
        HStack(spacing: 0) {
            ScreenTitle(Strings.recipesTitle)
                .accessibilityAddTraits(.isHeader)
            LibraryAddButton(onAdd: onAdd)
            Menu {
                if let onSelect {
                    Button(action: onSelect) { Label(Strings.select, systemImage: "checklist") }
                        .accessibilityIdentifier("library.select")
                }
                Button(action: onOpenSettings) { Label(Strings.navSettings, systemImage: "gearshape") }
                    .accessibilityIdentifier("library.settings")
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: size * 0.9, weight: .semibold))
                    .rotationEffect(.degrees(90))
                    .foregroundStyle(Palette.onBackground)
                    .frame(width: max(44, size + 16), height: max(44, size + 16))
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(Strings.moreOptions)
            .accessibilityIdentifier("library.more")
        }
        .offset(x: 6) // optical edge, past the button's own padding
    }
}

/// The views as one segmented shelf: Lists (in the paprika tone), then Not in a list, Cooked, Typed by you and
/// From a chat, each its icon, its count in Fraunces and its name. Each opens its own screen, as the strip's
/// cards did. The five share the width (Lists a little more); when a name's longest word would not fit
/// (German, or Dynamic Type), every cell takes the width it needs and the shelf scrolls sideways, so
/// nothing is ever clipped.
struct ViewsShelf: View {
    let counts: RecipeCounts?
    let listCount: Int
    let onOpenView: (RecipeFilter) -> Void
    let onOpenLists: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var width: CGFloat = 0

    static let countStyle = TextStyle(
        family: .fraunces, baseSize: 20, weight: 600, baseLineHeight: 22, relativeTo: .title3, tabularDigits: true
    )
    static let nameStyle = TextStyle(family: .karla, baseSize: 11.5, weight: 700, baseLineHeight: 13.5, relativeTo: .caption1)

    private struct Cell {
        let icon: String
        let name: String
        let count: Int?
        let id: String
        let lead: Bool
        let action: () -> Void
    }

    private var cells: [Cell] {
        [Cell(icon: "list.bullet", name: Strings.navLists, count: listCount, id: "recipes.lists", lead: true, action: onOpenLists)]
            + RecipeFilter.views.map { view in
                Cell(
                    icon: Self.icon(view), name: RecipeFilterPills.name(view), count: counts?.of(view),
                    id: "recipes.view.\(view.rawValue)", lead: false, action: { onOpenView(view) }
                )
            }
    }

    var body: some View {
        let cells = cells
        let weights = cells.indices.map { $0 == 0 ? 1.1 : 1.0 }
        let shares = weights.map { width * $0 / weights.reduce(0, +) }
        let needs = cells.map(need)
        let fits = width > 0 && zip(needs, shares).allSatisfy { $0 <= $1 }
        let widths = fits ? shares : zip(needs, shares).map { max($0, $1) }
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 0) {
                ForEach(Array(cells.enumerated()), id: \.element.id) { index, cell in
                    cellView(cell, divider: index > 0, width: widths[index])
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .background(Palette.surfaceContainer)
            .clipShape(RoundedRectangle(cornerRadius: 18))
        }
        .scrollDisabled(fits)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("recipes.viewsShelf")
    }

    /// What a cell needs: its widest word (names wrap between words), its count or its icon, and its padding.
    private func need(_ cell: Cell) -> CGFloat {
        let category = UIContentSizeCategory(dynamicTypeSize)
        let nameFont = Self.nameStyle.uiFont(for: category)
        let countFont = Self.countStyle.uiFont(for: category)
        let word = cell.name.split(separator: " ").map {
            (String($0) as NSString).size(withAttributes: [.font: nameFont]).width
        }.max() ?? 0
        let count = (String(cell.count ?? 0) as NSString).size(withAttributes: [.font: countFont]).width
        return ceil(max(word, count, 20)) + 10
    }

    private func cellView(_ cell: Cell, divider: Bool, width: CGFloat) -> some View {
        Button(action: cell.action) {
            VStack(spacing: 2) {
                Image(systemName: cell.icon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(cell.lead ? Palette.onPaprikaTone : Palette.accentText)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .frame(height: 20)
                Text(cell.count.map(String.init) ?? " ")
                    .textStyle(Self.countStyle)
                    .foregroundStyle(cell.lead ? Palette.onPaprikaTone : Palette.onBackground)
                    .lineLimit(1)
                Text(cell.name)
                    .textStyle(Self.nameStyle)
                    .foregroundStyle(cell.lead ? Palette.onPaprikaTone : Palette.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 4)
            .padding(.top, 10)
            .padding(.bottom, 9)
            .frame(width: width > 0 ? width : nil)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(cell.lead ? Palette.paprikaTone : Color.clear)
            .overlay(alignment: .leading) {
                if divider { Rectangle().fill(Palette.hairline).frame(width: 1) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(cell.count.map { "\(cell.name), \($0)" } ?? cell.name)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(cell.id)
    }

    static func icon(_ view: RecipeFilter) -> String {
        switch view {
        case .cooked: "checkmark.circle"
        case .typed: "pencil"
        case .chat: "bubble.left"
        default: "bookmark"
        }
    }
}

/// Search, with the filter button at its end (`onOpenFilters`; nil with the chips' flag off): the paprika
/// tone and a badge counting the filters on, once one is.
struct SearchFieldB: View {
    let query: String
    let onQueryChange: (String) -> Void
    let filtersOn: Int
    let onOpenFilters: (() -> Void)?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Palette.muted)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            TextField(Strings.searchRecipes, text: Binding(get: { query }, set: onQueryChange))
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.onBackground)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityIdentifier("recipes.search")
            if !query.isEmpty {
                Button { onQueryChange("") } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.muted)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .frame(minWidth: 32, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Strings.clearSearch)
            }
            if let onOpenFilters {
                FilterButton(on: filtersOn, action: onOpenFilters)
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, onOpenFilters == nil ? 14 : 4)
        .frame(minHeight: 52)
        .background(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.muted, lineWidth: 1))
    }
}

private struct FilterButton: View {
    let on: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(on > 0 ? Palette.onPaprikaTone : Palette.onBackground)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .frame(width: 44, height: 44)
                .background(on > 0 ? Palette.paprikaTone : Color.clear, in: RoundedRectangle(cornerRadius: 12))
                .overlay(alignment: .topTrailing) {
                    if on > 0 {
                        Text("\(on)")
                            .font(.system(size: 10.5, weight: .bold))
                            .foregroundStyle(Palette.onPrimary)
                            .dynamicTypeSize(...DynamicTypeSize.large)
                            .padding(.horizontal, 4)
                            .frame(minWidth: 16, minHeight: 16)
                            .background(Palette.primary, in: Capsule())
                            .offset(x: -3, y: 3)
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Strings.recipesFilterTitle)
        .accessibilityValue(on > 0 ? Strings.recipesFiltersOn(on) : "")
        .accessibilityIdentifier("recipes.filter")
    }
}

/// The filters that are on, as pills under search, each removed by a tap (its ×), then Clear; with the pantry
/// filter on, the line saying what it keeps. Nothing when no filter is on.
struct ActiveFilterPills: View {
    let active: [RecipeQuickFilter]
    let onRemove: (RecipeQuickFilter) -> Void
    let onClear: () -> Void

    var body: some View {
        if !active.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                PillFlow(spacing: 8, lineSpacing: 0) {
                    ForEach(active) { filter in pill(filter) }
                    Button(Strings.clear, action: onClear)
                        .buttonStyle(TextActionStyle())
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("recipes.filterClear")
                }
                if active.contains(.usesPantry) {
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

    private func pill(_ filter: RecipeQuickFilter) -> some View {
        let name = QuickFilterChips.name(filter)
        return Button { onRemove(filter) } label: {
            HStack(spacing: 6) {
                Text(name).textStyle(Self.pillStyle)
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            }
            .foregroundStyle(Palette.onPaprikaTone)
            .padding(.leading, 12)
            .padding(.trailing, 10)
            .frame(minHeight: 32)
            .background(Palette.paprikaTone, in: Capsule())
            // The 44pt touch target is the outer frame; the pill is the shape inside it.
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Strings.removeFilter(name))
        .accessibilityIdentifier("recipes.filterPill.\(filter.rawValue)")
    }

    static let pillStyle = TextStyle(family: .karla, baseSize: 14, weight: 700, baseLineHeight: 18, relativeTo: .subheadline)
}

/// The filter sheet: the three filters as switches (Not cooked yet left out inside the Cooked view), and Clear.
struct RecipeFilterSheet: View {
    let offered: [RecipeQuickFilter]
    let on: Set<RecipeQuickFilter>
    let onToggle: (RecipeQuickFilter) -> Void
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
                        .disabled(on.isEmpty)
                        .accessibilityIdentifier("recipes.filterSheet.clear")
                }
                .padding(.bottom, 4)
                ForEach(Array(offered.enumerated()), id: \.element) { index, filter in
                    SwitchRow(
                        title: QuickFilterChips.name(filter), description: Self.hint(filter),
                        isOn: Binding(get: { on.contains(filter) }, set: { _ in onToggle(filter) })
                    )
                    .accessibilityIdentifier("recipes.filterSwitch.\(filter.rawValue)")
                    if index < offered.count - 1 { Hairline() }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 16)
        }
        .background(Palette.background)
        .accessibilityIdentifier("recipes.filterSheet")
    }

    static func hint(_ filter: RecipeQuickFilter) -> String {
        switch filter {
        case .thirtyOrLess: Strings.recipesQuickThirtyOrLessHint
        case .notCooked: Strings.recipesQuickNotCookedHint
        case .usesPantry: Strings.recipesQuickPantryHint
        }
    }
}

/// Selecting, as design B draws it (#449): the count in Fraunces, Select all and Done, then Add to list and
/// Delete, pinned above the rows. Android's `SelectionBar` with Select all.
struct RecipesSelectionHeaderB: View {
    let count: Int
    let allSelected: Bool
    let onSelectAll: () -> Void
    let onDone: () -> Void
    let onAddToList: (() -> Void)?
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                Text(Strings.recipesSelectedCount(count))
                    .textStyle(Typography.headlineSmall)
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.updatesFrequently)
                    .accessibilityIdentifier("recipes.selectedCount")
                Button(Strings.selectAll, action: onSelectAll)
                    .buttonStyle(TextActionStyle(color: Palette.onBackground))
                    .disabled(allSelected)
                    .accessibilityIdentifier("recipes.selection.selectAll")
                Button(Strings.done, action: onDone)
                    .buttonStyle(TextActionStyle())
                    .accessibilityIdentifier("recipes.selection.done")
            }
            HStack(spacing: 8) {
                if let onAddToList {
                    Button(action: onAddToList) {
                        Label(Strings.addToListAction, systemImage: "bookmark")
                            .textStyle(Typography.labelLarge)
                            .foregroundStyle(Palette.onBackground)
                            .padding(.horizontal, 16)
                            .frame(minHeight: 40)
                            .background(Capsule().strokeBorder(Palette.muted, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(count == 0)
                    .opacity(count == 0 ? 0.4 : 1)
                    .accessibilityIdentifier("recipes.selection.addToList")
                }
                Button(action: onDelete) {
                    Text(Strings.delete)
                        .textStyle(Typography.labelLarge)
                        .foregroundStyle(Palette.onPrimary)
                        .padding(.horizontal, 18)
                        .frame(minHeight: 40)
                        .background(Capsule().fill(Palette.primary))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(count == 0)
                .opacity(count == 0 ? 0.4 : 1)
                .accessibilityIdentifier("recipes.selection.delete")
            }
            .padding(.bottom, 2)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(Palette.background)
        .overlay(alignment: .bottom) { Hairline() }
    }
}
