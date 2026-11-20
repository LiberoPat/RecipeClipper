import SwiftUI

/// "What I need" for the week shown (#51; Android's WhatINeedScreen): To buy, then In your
/// pantry. Each ingredient shows every planned line naming it, with its recipe and day.
/// Presence only: the pantry says you have flour, never that there's enough, and the screen
/// says so. An ingredient in the pantry with an amount shows it beside what the week's recipes
/// use, when that adds up exactly (#302): the two side by side, never compared.
struct WhatINeedScreen: View {
    let vm: WhatINeedViewModel
    /// The `shopForWeek` flag (#384): the title is "Shop for this week", as the menu item that opens it.
    var shopForWeek = false

    var body: some View {
        let state = vm.uiState
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    ScreenTitle(shopForWeek ? Strings.shopForWeek : Strings.whatINeedTitle)
                    Text(PlanDayFormat.weekRange(state.weekStart))
                        .textStyle(Typography.titleMedium)
                        .foregroundStyle(Palette.muted)
                    Hairline().padding(.top, 8)
                }
                .padding(.top, 4)
                if let needs = state.needs {
                    if needs.isEmpty {
                        muted(Strings.groceriesWeekEmpty).padding(.top, 16)
                    } else {
                        // Fresh food (#396): with perishables for meals 4+ days away, To buy is two trips.
                        let trips = state.trips.flatMap { $0.later.isEmpty ? nil : $0 }
                        let buyRows = trips?.now ?? needs.buy
                        VStack(alignment: .leading, spacing: 8) {
                            SectionHeading(trips != nil ? Strings.whatINeedBuyNow : Strings.whatINeedBuy).accessibilityAddTraits(.isHeader)
                            if buyRows.isEmpty {
                                muted(trips != nil ? Strings.whatINeedNothingToBuyNow : Strings.whatINeedNothingToBuy)
                            } else {
                                addButton(added: state.added, label: Strings.addToGroceries, id: "addBuyToGroceries", action: vm.onAddBuyToGroceries)
                            }
                        }
                        .padding(.top, 18)
                        ForEach(keyed(buyRows, "buy"), id: \.id) { NeedRowView(row: $0.row) }
                        if let trips {
                            VStack(alignment: .leading, spacing: 8) {
                                SectionHeading(Strings.whatINeedBuyLater).accessibilityAddTraits(.isHeader)
                                muted(Strings.whatINeedBuyLaterNote)
                                addButton(added: state.addedLater, label: Strings.whatINeedAddLater, id: "addLaterToGroceries", action: vm.onAddLaterToGroceries)
                            }
                            .padding(.top, 24)
                            // Ids unique across the whole stack ("later-0"), as the other sections' (#185).
                            ForEach(trips.later.enumerated().map { (id: "later-\($0.offset)", later: $0.element) }, id: \.id) {
                                NeedRowView(row: $0.later.row, later: $0.later)
                            }
                        }
                        if !needs.have.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                SectionHeading(Strings.whatINeedHave).accessibilityAddTraits(.isHeader)
                                muted(Strings.whatINeedNote)
                            }
                            .padding(.top, 24)
                            ForEach(keyed(needs.have, "have"), id: \.id) { NeedRowView(row: $0.row) }
                        }
                    }
                } else {
                    ProgressView().tint(Palette.primary).padding(16)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
            .readableColumn()
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
    }

    private func muted(_ text: String) -> some View {
        Text(text).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
    }

    private func addButton(added: Bool, label: String, id: String, action: @escaping () -> Void) -> some View {
        Button(added ? Strings.whatINeedAdded : label, action: action)
            .buttonStyle(PrimaryButtonStyle())
            .disabled(added)
            .accessibilityIdentifier(id)
    }

    /// Ids unique across the whole stack, as Android keys them ("buy-0", "have-0"): a lazy stack
    /// pools every ForEach's ids, so bare offsets in both sections hid the first pantry rows (#185).
    private func keyed(_ rows: [NeedRow], _ section: String) -> [(id: String, row: NeedRow)] {
        rows.enumerated().map { ("\(section)-\($0.offset)", $0.element) }
    }
}

/// One ingredient of the week; `later` (#396) adds the day it is first needed and what its group keeps.
private struct NeedRowView: View {
    let row: NeedRow
    var later: LaterRow?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(row.name ?? row.lines[0].text).textStyle(Typography.bodyLargeBold).foregroundStyle(Palette.onBackground)
            if let later {
                muted(Strings.whatINeedLaterFor(PlanDayFormat.title(later.firstDay))).accessibilityIdentifier("laterFor")
                muted(Strings.freshKeeps(later.group.keeps))
            }
            switch row.status {
            case .have:
                muted(Strings.whatINeedYouHave(row.pantryName ?? ""))
                // #302: the pantry's amount as written, beside the week's exact total; never compared.
                if let have = row.pantryQuantity {
                    muted(row.weekTotal.map { Strings.whatINeedPantryAndWeek(have, $0) } ?? Strings.whatINeedPantryAmount(have))
                }
            case .staple: muted(Strings.pantryAlwaysHave)
            case .buy: EmptyView()
            }
            ForEach(Array(row.lines.enumerated()), id: \.offset) { _, line in
                Text(line.text).textStyle(Typography.bodyMedium).foregroundStyle(Palette.onBackground)
                muted(line.day.map { "\(line.title) · \(PlanDayFormat.shortWeekday($0))" } ?? line.title)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 12)
    }

    private func muted(_ text: String) -> some View {
        Text(text).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
    }
}
