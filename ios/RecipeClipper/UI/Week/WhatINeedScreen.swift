import SwiftUI

/// "What I need" for the week shown (#51; Android's WhatINeedScreen): To buy, then In your
/// pantry. Each ingredient shows every planned line naming it, with its recipe and day.
/// Presence only: the pantry says you have flour, never that there's enough, and the screen
/// says so.
struct WhatINeedScreen: View {
    let vm: WhatINeedViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    ScreenTitle(Strings.whatINeedTitle)
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
                        VStack(alignment: .leading, spacing: 8) {
                            SectionHeading(Strings.whatINeedBuy).accessibilityAddTraits(.isHeader)
                            if needs.buy.isEmpty {
                                muted(Strings.whatINeedNothingToBuy)
                            } else {
                                Button(state.added ? Strings.whatINeedAdded : Strings.addToGroceries, action: vm.onAddBuyToGroceries)
                                    .buttonStyle(PrimaryButtonStyle())
                                    .disabled(state.added)
                                    .accessibilityIdentifier("addBuyToGroceries")
                            }
                        }
                        .padding(.top, 18)
                        ForEach(Array(needs.buy.enumerated()), id: \.offset) { _, row in NeedRowView(row: row) }
                        if !needs.have.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                SectionHeading(Strings.whatINeedHave).accessibilityAddTraits(.isHeader)
                                muted(Strings.whatINeedNote)
                            }
                            .padding(.top, 24)
                            ForEach(Array(needs.have.enumerated()), id: \.offset) { _, row in NeedRowView(row: row) }
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
}

private struct NeedRowView: View {
    let row: NeedRow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(row.name ?? row.lines[0].text).textStyle(Typography.bodyLargeBold).foregroundStyle(Palette.onBackground)
            switch row.status {
            case .have: muted(Strings.whatINeedYouHave(row.pantryName ?? ""))
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
