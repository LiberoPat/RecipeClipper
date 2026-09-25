import SwiftUI

/// The Pantry tab (#51; Android's PantryScreen): "Add to the pantry", a search field, then
/// everything by aisle (or by expiry, from the menu). Each row's switch says whether it's in
/// stock; tapping the row opens its edit sheet (quantity, staple, use-by date, delete).
struct PantryScreen: View {
    let vm: PantryViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 8) {
                    ScreenTitle(Strings.tabPantry)
                    OutlinedField(
                        label: Strings.pantryAddHint,
                        text: Binding(get: { vm.uiState.draft }, set: vm.onDraftChange),
                        onSubmit: vm.onAddTyped
                    )
                    .accessibilityIdentifier("pantryDraft")
                    if state.hasItems {
                        OutlinedField(
                            label: Strings.pantrySearchHint,
                            text: Binding(get: { vm.uiState.query }, set: vm.onQueryChange)
                        )
                        .accessibilityIdentifier("pantrySearch")
                    }
                    if let empty = emptyText(state) {
                        Text(empty)
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.muted)
                            .padding(.top, 8)
                    }
                }
                .padding(.top, 4)
                ForEach(Array((state.sections ?? []).enumerated()), id: \.offset) { _, section in
                    VStack(alignment: .leading, spacing: 4) {
                        if let aisle = section.aisle { SectionHeading(Strings.aisle(aisle)) }
                        Hairline()
                    }
                    .padding(.top, 18)
                    .padding(.bottom, 2)
                    .accessibilityAddTraits(.isHeader)
                    ForEach(section.items) { item in
                        PantryRow(item: item, today: state.today, vm: vm)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
            .readableColumn()
        }
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // An exclusive choice, so radio glyphs rather than a bare checkmark.
                Menu {
                    sortButton(.aisle, Strings.pantrySortAisle, current: state.sort)
                    sortButton(.expiry, Strings.pantrySortExpiry, current: state.sort)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(Strings.moreOptions)
            }
        }
        .overlay(alignment: .bottom) {
            if let message = state.message {
                snackbar(message)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.message == nil)
        .task(id: state.message) {
            guard state.message != nil else { return }
            await SnackbarTimeout.run(pending: ["message"], onTimeout: vm.onMessageDismissed)
        }
        .sheet(isPresented: Binding(get: { state.editing != nil }, set: { if !$0 { vm.onEditDismissed() } })) {
            if let editing = vm.uiState.editing {
                PantryEditSheet(editing: editing, vm: vm)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    private func emptyText(_ state: PantryUiState) -> String? {
        guard let sections = state.sections else { return nil }
        if !state.hasItems { return Strings.pantryEmpty }
        if sections.isEmpty { return Strings.pantryNoResults(state.query.kTrimmed) }
        return nil
    }

    private func sortButton(_ sort: PantrySort, _ title: String, current: PantrySort) -> some View {
        Button { vm.onSortChange(sort) } label: {
            Label(title, systemImage: sort == current ? "largecircle.fill.circle" : "circle")
        }
    }

    @ViewBuilder
    private func snackbar(_ message: PantryMessage) -> some View {
        switch message {
        case .outOfStock(_, let item):
            Snackbar(message: Strings.pantryOutSnackbar(item.name), actionLabel: Strings.addToGroceries) {
                vm.onAddToGroceries(item)
            }
        case .deleted(_, let name):
            Snackbar(message: Strings.pantryDeleted(name), actionLabel: Strings.undo, action: vm.onUndoDelete)
        case .addedToGroceries(_, let name):
            Snackbar(message: Strings.addedToGroceries(name), actionLabel: nil)
        }
    }
}

private struct PantryRow: View {
    let item: PantryItem
    let today: Int64
    let vm: PantryViewModel

    var body: some View {
        HStack(spacing: 12) {
            Button { vm.onEdit(item) } label: {
                VStack(alignment: .leading, spacing: 0) {
                    Text(item.name)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(item.inStock ? Palette.onBackground : Palette.muted)
                    let details = [item.quantity, item.alwaysHave ? Strings.pantryAlwaysHave : nil, item.inStock ? nil : Strings.pantryOut]
                        .compactMap { $0 }
                    if !details.isEmpty {
                        Text(details.joined(separator: " · ")).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                    }
                    if let day = item.expiresDay {
                        let badge = PantryList.badge(day, today: today)
                        Text(badge == .expired ? Strings.pantryExpired : Strings.pantryUseBy(PantryDate.short(day)))
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(badge != nil ? Palette.accentText : Palette.muted)
                            .accessibilityIdentifier("expiry-\(item.id)")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Toggle(Strings.pantryInStock, isOn: Binding(get: { item.inStock }, set: { _ in vm.onToggleStock(item) }))
                .labelsHidden()
                .tint(Palette.primary)
                .accessibilityLabel("\(Strings.pantryInStock): \(item.name)")
                .accessibilityIdentifier("inStock-\(item.id)")
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("pantry-\(item.id)")
    }
}

/// Use-by dates. An expiry is an epoch day, formatted at midnight UTC like the plan's days.
enum PantryDate {
    static func short(_ day: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.setLocalizedDateFormatFromTemplate("MMMd")
        return formatter.string(from: PlanDays.utcDate(day))
    }

    /// The epoch day of a date picked in a UTC calendar.
    static func day(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 / 86_400).rounded(.down)) }
}

private struct PantryEditSheet: View {
    let editing: PantryEditing
    let vm: PantryViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeading(Strings.pantryEditTitle)
                OutlinedField(
                    label: Strings.pantryLabelName,
                    text: Binding(get: { vm.uiState.editing?.name ?? "" }, set: vm.onEditName)
                )
                .accessibilityIdentifier("pantryEditName")
                OutlinedField(
                    label: Strings.pantryLabelQuantity,
                    text: Binding(get: { vm.uiState.editing?.quantity ?? "" }, set: vm.onEditQuantity)
                )
                .accessibilityIdentifier("pantryEditQuantity")
                Toggle(isOn: Binding(get: { vm.uiState.editing?.alwaysHave ?? false }, set: vm.onEditAlwaysHave)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Strings.pantryAlwaysHave).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                        Text(Strings.pantryAlwaysHaveDetail).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                    }
                }
                .tint(Palette.primary)
                .accessibilityIdentifier("pantryEditAlwaysHave")
                expiry
                if let bought = editing.purchasedDay {
                    Text(Strings.pantryBought(PantryDate.short(bought))).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                }
                HStack {
                    Button(Strings.delete, role: .destructive, action: vm.onEditDelete)
                        .buttonStyle(TextActionStyle(color: Palette.accentText))
                        .accessibilityIdentifier("pantryEditDelete")
                    Spacer()
                    Button(Strings.save, action: vm.onEditSave)
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled((vm.uiState.editing?.name ?? "").kIsBlank)
                        .accessibilityIdentifier("pantryEditSave")
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .presentationBackground(Palette.background)
    }

    /// The use-by date: none, or a date picked in a UTC calendar (an epoch day's own).
    @ViewBuilder
    private var expiry: some View {
        let day = vm.uiState.editing?.expiresDay
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Strings.pantryLabelExpiry).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                if day == nil {
                    Text(Strings.pantryNoDate).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                }
            }
            Spacer()
            if let day {
                DatePicker(
                    Strings.pantryLabelExpiry,
                    selection: Binding(get: { PlanDays.utcDate(day) }, set: { vm.onEditExpiry(PantryDate.day($0)) }),
                    displayedComponents: .date
                )
                .labelsHidden()
                .environment(\.timeZone, TimeZone(identifier: "UTC")!)
                Button(Strings.clearDate) { vm.onEditExpiry(nil) }
                    .buttonStyle(TextActionStyle(color: Palette.accentText))
            } else {
                Button(Strings.setDate) { vm.onEditExpiry(vm.uiState.today) }
                    .buttonStyle(TextActionStyle(color: Palette.accentText))
            }
        }
    }
}
