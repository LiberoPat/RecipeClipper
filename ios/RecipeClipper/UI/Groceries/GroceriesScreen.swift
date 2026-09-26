import SwiftUI

/// The Groceries tab (#50; Android's GroceriesScreen): "Add an item", then the list by aisle.
/// Lines naming the same ingredient sit together under its name, or as one added-up row when
/// that's exact. Tap to tick; long-press to move to another aisle or delete (with undo). The
/// menu sends the list as plain text and pastes one in (#149). While anything is ticked, "Done
/// shopping" (#146) puts it away in the pantry and clears it, with undo.
///
/// `receiveVM` backs "Add this list" for a pasted list; nil leaves "Paste a list" out.
/// `onOpenPantry` shows the pantry once lines were added to it.
struct GroceriesScreen: View {
    let vm: GroceriesViewModel
    var receiveVM: ReceiveListViewModel? = nil
    var onOpenPantry: () -> Void = {}

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
                    if let receiveVM {
                        Button { receiveVM.open(UIPasteboard.general.string) } label: {
                            Label(Strings.pasteList, systemImage: "doc.on.clipboard")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(Strings.moreOptions)
            }
        }
        // Snackbars only for undo (#146): a delete, or "Done shopping". "Done shopping" itself
        // shows while anything is ticked; the snackbar sits above it.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                if let removed = state.removed {
                    Snackbar(
                        message: removed.label.map(Strings.groceryDeleted)
                            ?? (removed.putAway ? Strings.doneShoppingCleared : Strings.checkedCleared),
                        actionLabel: Strings.undo,
                        action: vm.onUndoRemove
                    )
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if state.hasChecked {
                    Button(Strings.doneShopping, action: vm.onDoneShopping)
                        .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                        .accessibilityIdentifier("doneShopping")
                        .frame(maxWidth: ReadableWidth.column)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity)
                        .background(Palette.background)
                }
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.removed == nil)
        .task(id: state.removed) {
            guard let removed = state.removed else { return }
            await SnackbarTimeout.run(pending: ["\(removed.id)"], onTimeout: vm.onSnackbarDismissed)
        }
        .sheet(isPresented: Binding(get: { state.moving != nil }, set: { if !$0 { vm.onMoveDismissed() } })) {
            if let moving = vm.uiState.moving {
                MoveToAisleSheet(current: moving.items[0].aisle, onSelect: vm.onMoveTo)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
        .sheet(isPresented: Binding(get: { state.putAway != nil }, set: { if !$0 { vm.onPutAwayDismissed() } })) {
            if let sheet = vm.uiState.putAway {
                PutAwaySheetView(sheet: sheet, vm: vm)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
        .modifier(ReceiveListSheet(vm: receiveVM, onAddedToPantry: onOpenPantry))
    }
}

/// "Add this list" in a sheet while the receive ViewModel has lines; it closes once they're
/// added, and lines added to the pantry show the pantry.
private struct ReceiveListSheet: ViewModifier {
    let vm: ReceiveListViewModel?
    let onAddedToPantry: () -> Void

    func body(content: Content) -> some View {
        if let vm {
            content
                .sheet(isPresented: Binding(get: { vm.uiState.lines != nil }, set: { if !$0 { vm.onDismiss() } })) {
                    ScrollView {
                        ReceiveListView(vm: vm)
                            .padding(.horizontal, 20)
                            .readableColumn()
                            .padding(.vertical, 24)
                    }
                    .presentationBackground(Palette.background)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
                }
                .onChange(of: vm.uiState.added) { _, added in
                    guard let added else { return }
                    vm.onDismiss()
                    if added == .pantry { onAddedToPantry() }
                }
        } else {
            content
        }
    }
}

/// "Done shopping" (#146): the ticked items the pantry can hold, with checkboxes (what it tracks
/// starts ticked), and one button that puts the ticked ones away and clears every ticked line.
private struct PutAwaySheetView: View {
    let sheet: PutAwaySheet
    let vm: GroceriesViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeading(Strings.doneShopping).padding(.bottom, 4)
                Text(Strings.putAwayIntro)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 8)
                ForEach(sheet.items) { item in
                    let ticked = sheet.ticked.contains(item.key)
                    Button { vm.onPutAwayToggle(item.key) } label: {
                        HStack(spacing: 12) {
                            CheckboxGlyph(checked: ticked)
                            Text(item.name).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(ticked ? [.isSelected] : [])
                    .accessibilityIdentifier("putAway-\(item.key)")
                }
                Button(Strings.putAway, action: vm.onPutAwayConfirm)
                    .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                    .padding(.top, 12)
                    .accessibilityIdentifier("putAwayButton")
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.vertical, 24)
        }
        .presentationBackground(Palette.background)
    }
}

/// A row on the list, always one tick. An added-up total shows its lines under it; lines that
/// can't be added up honestly sit under their ingredient's name, as written, without ticks of
/// their own: ticking the row ticks them all.
private struct GroceryRowView: View {
    let row: GroceryCombiner.Row
    let vm: GroceriesViewModel

    var body: some View {
        switch row {
        case .single(let item):
            CheckLine(text: item.text, detail: [], checked: item.checked, row: row, label: item.text, vm: vm)
        case .combined(_, let text, let items):
            // "2 corn × 3" under "6 corn"; nothing under a line that is its own detail.
            CheckLine(
                text: text, detail: [GroceryCombiner.lines(row).joined(separator: " + ")].filter { $0 != text },
                checked: items.allSatisfy(\.checked), row: row, label: text, vm: vm
            )
        case .together(let name, let items):
            // One tick for the ingredient; its lines, as written, have none of their own.
            CheckLine(
                text: name, detail: GroceryCombiner.lines(row),
                checked: items.allSatisfy(\.checked), row: row, label: name, vm: vm
            )
        }
    }
}

private struct CheckLine: View {
    let text: String
    let detail: [String]
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
                    ForEach(Array(detail.enumerated()), id: \.offset) { _, line in
                        Text(line).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
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
