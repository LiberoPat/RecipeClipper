import SwiftUI

/// The Pantry tab (#51; Android's PantryScreen): "Add to the pantry", a search field, then
/// everything by aisle (or by expiry, from the menu), and what has run out last (#194). Each row
/// shows whether it's in stock, running low or run out, and its quantity as written; tapping the
/// row opens its edit sheet (stock, quantity, staple, use-by date, delete), with swipes and a
/// touch-and-hold menu as shortcuts. The menu sends what's in stock, as plain text or as a file (#149), and
/// clears what has run out after asking, with undo (#194). A `List`, for the
/// swipe actions, so the readable column is made from the width, as on Recipes.
struct PantryScreen: View {
    let vm: PantryViewModel
    /// Makes "Send as file" (#149, phase 2); nil leaves it out. Made on first use.
    var makeSendFileVM: (() -> SendFileViewModel)? = nil
    @State private var sendFileVM: SendFileViewModel?
    /// A List's rows take insets, not a frame, so the readable column is made from the width.
    @State private var sideInset = ReadableWidth.gutter

    var body: some View {
        let state = vm.uiState
        List {
            Group {
                VStack(alignment: .leading, spacing: 8) {
                    ScreenTitle(Strings.tabPantry)
                    OutlinedField(
                        label: Strings.pantryAddHint,
                        text: Binding(get: { vm.uiState.draft }, set: vm.onDraftChange),
                        onSubmit: vm.onAddTyped
                    )
                    .accessibilityIdentifier("pantryDraft")
                    .tooltipAnchor(.pantryAdd)
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
                .listRowSeparator(.hidden)
                // One flat ForEach of headings and items, each with its own id ("heading-runOut",
                // "item-12"), not a ForEach of items nested in a ForEach of sections: nested, an
                // item's row id included its section, so a move between sections was a delete and
                // an insert, which a List can leave drawn in a stale slot (#203). Flat, the row
                // keeps its id and moves.
                ForEach(PantryListRow.rows(state.sections ?? [])) { row in
                    switch row {
                    case .heading(let section):
                        heading(section)
                    case .item(let item):
                        itemRow(item, state: state)
                    }
                }
                Color.clear.frame(height: 32).listRowSeparator(.hidden)
            }
            .listRowInsets(EdgeInsets(top: 0, leading: sideInset, bottom: 0, trailing: sideInset))
            .listRowBackground(Palette.background)
        }
        .listStyle(.plain)
        .environment(\.defaultMinListRowHeight, 0)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width in
            sideInset = ReadableWidth.inset(in: width)
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar(state) }
        // The tooltips (#190); none over this screen's sheet and snackbar. Outside the toolbar,
        // whose menu is an anchor too.
        .tooltipHost(.pantry, blocked: state.editing != nil || state.message != nil || state.confirmClearRunOut != nil)
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
        .alert(
            Strings.clearRunOutTitle(state.confirmClearRunOut ?? 0),
            isPresented: Binding(get: { vm.uiState.confirmClearRunOut != nil }, set: { if !$0 { vm.onClearRunOutDismissed() } })
        ) {
            Button(Strings.clear, role: .destructive, action: vm.onClearRunOutConfirm)
            Button(Strings.cancel, role: .cancel, action: vm.onClearRunOutDismissed)
        }
        .modifier(SendFileEffect(vm: sendFileVM))
    }

    private func heading(_ section: PantrySection) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if section.runOut {
                // Run out sits last, dimmed (#194).
                Text(Strings.pantryOut)
                    .textStyle(Typography.titleMedium)
                    .foregroundStyle(Palette.muted)
            } else if let aisle = section.aisle {
                SectionHeading(Strings.aisle(aisle))
            }
            Hairline()
        }
        .padding(.top, 18)
        .padding(.bottom, 2)
        .accessibilityAddTraits(.isHeader)
        .listRowSeparator(.hidden)
    }

    private func itemRow(_ item: PantryItem, state: PantryUiState) -> some View {
        // The stock tooltip (#190) points at the first row.
        PantryRow(
            item: item, today: state.today, onList: state.onList.contains(item.id),
            first: item.id == state.sections?.first?.items.first?.id, vm: vm
        )
        .listRowSeparator(.hidden)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if item.stock != .inStock {
                Button { afterSwipeCloses { vm.onSetStock(item, .inStock) } } label: {
                    Label(Strings.pantryRestock, systemImage: "arrow.uturn.backward")
                }
                .tint(Palette.primary)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if item.stock != .runOut {
                Button { afterSwipeCloses { vm.onSetStock(item, .runOut) } } label: {
                    Label(Strings.pantryRanOut, systemImage: "xmark.circle")
                }
                .tint(Palette.muted)
            }
            if item.stock == .inStock {
                Button { afterSwipeCloses { vm.onSetStock(item, .runningLow) } } label: {
                    Label(Strings.pantryRunningLow, systemImage: "gauge.with.dots.needle.33percent")
                }
                .tint(Palette.accentText)
            }
        }
    }

    /// A swipe action's change waits until its row has closed (#203). Applied at once, the row
    /// moves to another section while UIKit is still animating its swipe shut, and the List can
    /// leave that cell drawn at its old place (over the Run out heading, a blank slot where it
    /// belongs) although the tree is right. The menu and sheet change it at once.
    private func afterSwipeCloses(_ change: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: change)
    }

    @ToolbarContentBuilder
    private func toolbar(_ state: PantryUiState) -> some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                // What's in stock, as text or as a file (#149); shown only while anything is.
                if let text = vm.shareText(title: Strings.tabPantry, aisleName: Strings.aisle) {
                    ShareLink(item: text, subject: Text(Strings.tabPantry)) {
                        Label(Strings.shareGroceries, systemImage: "square.and.arrow.up")
                    }
                    if let makeSendFileVM {
                        Button {
                            let send = sendFileVM ?? makeSendFileVM()
                            sendFileVM = send
                            send.sendPantry(title: Strings.tabPantry)
                        } label: {
                            Label(Strings.sendFile, systemImage: "doc")
                        }
                    }
                    Divider()
                }
                // An exclusive choice, so radio glyphs rather than a bare checkmark.
                sortButton(.aisle, Strings.pantrySortAisle, current: state.sort)
                sortButton(.expiry, Strings.pantrySortExpiry, current: state.sort)
                Divider()
                // Asks first; the grocery list is left alone, and Undo puts the items back (#194).
                Button(action: vm.onClearRunOut) {
                    Label(Strings.clearRunOut, systemImage: "trash")
                }
                .disabled(!state.hasRunOut)
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel(Strings.moreOptions)
            .tooltipAnchor(.pantryMenu, inToolbar: true)
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

    /// Snackbars only for undo (#146): a delete, "Clear run-out items" (#194), or the tag's removal.
    @ViewBuilder
    private func snackbar(_ message: PantryMessage) -> some View {
        switch message {
        case .deleted(_, let name):
            Snackbar(message: Strings.pantryDeleted(name), actionLabel: Strings.undo, action: vm.onUndoDelete)
        case .runOutCleared:
            Snackbar(message: Strings.runOutCleared, actionLabel: Strings.undo, action: vm.onUndoDelete)
        case .takenOffList(_, let count):
            Snackbar(message: Strings.takenOffList(count), actionLabel: Strings.undo, action: vm.onUndoDelete)
        }
    }
}

/// One row of the Pantry's List: a section's heading or an item (#203). Ids are unique across
/// the whole List (#185) and an item's doesn't name its section, so a move keeps its row.
enum PantryListRow: Identifiable {
    case heading(PantrySection)
    case item(PantryItem)

    var id: String {
        switch self {
        case .heading(let section):
            return "heading-" + (section.runOut ? "runOut" : "aisle-\(section.aisle?.key ?? "expiry")")
        case .item(let item):
            return "item-\(item.id)"
        }
    }

    static func rows(_ sections: [PantrySection]) -> [PantryListRow] {
        sections.flatMap { [.heading($0)] + $0.items.map(PantryListRow.item) }
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
