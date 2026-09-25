import SwiftUI

/// The Groceries tab (#50; Android's GroceriesScreen): "Add an item", then the list by aisle.
/// Lines naming the same ingredient sit together under its name, or as one added-up row when
/// that's exact. Tap to tick; long-press to move to another aisle or delete (with undo). The
/// menu shares the list as plain text and clears what's ticked.
struct GroceriesScreen: View {
    let vm: GroceriesViewModel

    var body: some View {
        let state = vm.uiState
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 8) {
                    ScreenTitle(Strings.tabGroceries)
                    OutlinedField(
                        label: Strings.groceriesAddHint,
                        text: Binding(get: { vm.uiState.draft }, set: vm.onDraftChange),
                        onSubmit: vm.onAddTyped
                    )
                    if state.isEmpty {
                        Text(Strings.groceriesEmpty)
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.muted)
                            .padding(.top, 8)
                    }
                }
                .padding(.top, 4)
                ForEach(state.sections ?? []) { section in
                    VStack(alignment: .leading, spacing: 4) {
                        SectionHeading(Strings.aisle(section.aisle))
                        Hairline()
                    }
                    .padding(.top, 18)
                    .padding(.bottom, 2)
                    .accessibilityAddTraits(.isHeader)
                    ForEach(section.rows) { row in
                        GroceryRowView(row: row, vm: vm)
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
                Menu {
                    if let text = vm.shareText(title: Strings.tabGroceries, aisleName: Strings.aisle) {
                        ShareLink(item: text, subject: Text(Strings.tabGroceries)) {
                            Label(Strings.shareGroceries, systemImage: "square.and.arrow.up")
                        }
                    }
                    Button { vm.onClearChecked() } label: {
                        Label(Strings.clearChecked, systemImage: "checkmark.circle")
                    }
                    .disabled(!state.hasChecked)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(Strings.moreOptions)
            }
        }
        .overlay(alignment: .bottom) {
            if let removed = state.removed {
                Snackbar(
                    message: removed.label.map(Strings.groceryDeleted) ?? Strings.checkedCleared,
                    actionLabel: Strings.undo,
                    action: vm.onUndoRemove
                )
                .frame(maxWidth: ReadableWidth.column)
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else if let offer = state.pantryOffer {
                pantrySnackbar(offer)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.removed == nil)
        .animation(.easeOut(duration: 0.2), value: state.pantryOffer == nil)
        .task(id: state.removed) {
            guard let removed = state.removed else { return }
            await SnackbarTimeout.run(pending: ["\(removed.id)"], onTimeout: vm.onSnackbarDismissed)
        }
        .task(id: state.pantryOffer) {
            guard state.pantryOffer != nil else { return }
            await SnackbarTimeout.run(pending: ["pantry"], onTimeout: vm.onPantryOfferDismissed)
        }
        .sheet(isPresented: Binding(get: { state.moving != nil }, set: { if !$0 { vm.onMoveDismissed() } })) {
            if let moving = vm.uiState.moving {
                MoveToAisleSheet(current: moving.items[0].aisle, onSelect: vm.onMoveTo)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }
}

extension GroceriesScreen {
    /// Ticking off fed the pantry (#51): a restock with Undo, or an offer to add.
    @ViewBuilder
    fileprivate func pantrySnackbar(_ offer: PantryOffer) -> some View {
        switch offer {
        case .restocked(_, let name):
            Snackbar(message: Strings.pantryRestocked(name), actionLabel: Strings.undo, action: vm.onUndoRestock)
        case .offer(_, let name, _):
            Snackbar(message: Strings.offerPantry(name), actionLabel: Strings.addToPantry, action: vm.onAddToPantry)
        }
    }
}

/// A row on the list. A line on its own or an added-up total is one tick; lines that can't be
/// added up honestly sit under their ingredient's name, each with its own tick.
private struct GroceryRowView: View {
    let row: GroceryCombiner.Row
    let vm: GroceriesViewModel

    var body: some View {
        switch row {
        case .single(let item):
            CheckLine(text: item.text, detail: nil, checked: item.checked, row: row, label: item.text, vm: vm)
        case .combined(_, let text, let items):
            CheckLine(
                text: text, detail: items.map(\.text).joined(separator: " + "),
                checked: items.allSatisfy(\.checked), row: row, label: text, vm: vm
            )
        case .together(let name, let items):
            VStack(alignment: .leading, spacing: 0) {
                Text(name)
                    .textStyle(Typography.titleSmall)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 8)
                ForEach(items) { item in
                    CheckLine(text: item.text, detail: nil, checked: item.checked, row: .single(item), label: item.text, vm: vm)
                }
            }
        }
    }
}

private struct CheckLine: View {
    let text: String
    let detail: String?
    let checked: Bool
    let row: GroceryCombiner.Row
    let label: String
    let vm: GroceriesViewModel

    var body: some View {
        Button { vm.onToggle(row) } label: {
            HStack(spacing: 12) {
                CheckboxGlyph(checked: checked)
                VStack(alignment: .leading, spacing: 0) {
                    Text(text)
                        .textStyle(Typography.bodyLarge)
                        .strikethrough(checked)
                        .foregroundStyle(checked ? Palette.muted : Palette.onBackground)
                    if let detail {
                        Text(detail).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? [.isSelected] : [])
        .accessibilityIdentifier("grocery-\(row.id)")
        .contextMenu {
            Button { vm.onMoveStart(row) } label: { Label(Strings.moveToAisle, systemImage: "arrow.right") }
            Button(role: .destructive) { vm.onDelete(row, label: label) } label: {
                Label(Strings.delete, systemImage: "trash")
            }
        }
    }
}

/// Long-press → "Move to aisle…": every aisle, an exclusive choice, so radio rows.
private struct MoveToAisleSheet: View {
    let current: Aisle
    let onSelect: (Aisle) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.moveToAisleTitle).padding(.bottom, 8)
                ForEach(Aisle.allCases) { aisle in
                    Button { onSelect(aisle) } label: {
                        HStack(spacing: 12) {
                            RadioGlyph(selected: aisle == current)
                            Text(Strings.aisle(aisle)).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                            Spacer()
                        }
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(aisle == current ? [.isSelected] : [])
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .presentationBackground(Palette.background)
    }
}

/// "Add to groceries" (#50, Paprika's basket): every line to buy, grouped by recipe (with its
/// day, from the Week), each ticked to start. Untick what's already in the cupboard, then one
/// button adds the rest. Closes once they're added.
struct AddToGroceriesSheet: View {
    let vm: AddToGroceriesViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = vm.uiState
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.addToGroceriesTitle).padding(.bottom, 8)
                if let sources = state.sources {
                    if sources.isEmpty {
                        Text(Strings.groceriesWeekEmpty).textStyle(Typography.bodyMedium).foregroundStyle(Palette.muted)
                    } else {
                        // One recipe from its own screen needs no heading; the week's are headed.
                        let headed = sources.count > 1 || sources[0].day != nil
                        ForEach(sources) { source in
                            if headed {
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(source.title).textStyle(Typography.bodyLargeBold).foregroundStyle(Palette.onBackground)
                                    if let day = source.day {
                                        Text(PlanDayFormat.title(day)).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                                    }
                                }
                                .padding(.top, 12)
                                .padding(.bottom, 2)
                            }
                            ForEach(Array(source.lines.enumerated()), id: \.offset) { index, line in
                                let id = SourceLine(source: source.key, index: index)
                                let ticked = !state.unticked.contains(id)
                                Button { vm.onToggle(id) } label: {
                                    HStack(spacing: 12) {
                                        CheckboxGlyph(checked: ticked)
                                        Text(line).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    .padding(.vertical, 4)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityAddTraits(ticked ? [.isSelected] : [])
                                .accessibilityIdentifier("sheetLine-\(source.key)-\(index)")
                            }
                        }
                        Button(Strings.addToGroceries, action: vm.onAdd)
                            .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                            .disabled(state.tickedCount == 0 || state.added)
                            .padding(.top, 12)
                            .accessibilityIdentifier("addToGroceriesButton")
                    }
                } else {
                    ProgressView().tint(Palette.primary).padding(16)
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .presentationBackground(Palette.background)
        .onChange(of: state.added) { _, added in
            if added { dismiss() }
        }
    }
}
