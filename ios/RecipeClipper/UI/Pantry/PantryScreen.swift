import SwiftUI

/// The Pantry tab (#51; Android's PantryScreen): "Add to the pantry", a search field, then
/// everything by aisle (or by expiry, from the menu), and what has run out last (#194). Each row
/// shows whether it's in stock, running low or run out, with a labelled action, swipes and a
/// touch-and-hold menu; tapping the row opens its edit sheet (quantity, staple, use-by date,
/// delete). The menu sends what's in stock, as plain text or as a file (#149). A `List`, for the
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
                // Keyed by name, not offset: a List pools ids, and an Int offset would equal an
                // item's Int64 id (#185).
                ForEach(state.sections ?? [], id: \.key) { section in
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
                    ForEach(section.items) { item in
                        // The stock tooltip (#190) points at the first row's action.
                        PantryRow(
                            item: item, today: state.today, onList: state.onList.contains(item.id),
                            first: item.id == state.sections?.first?.items.first?.id, vm: vm
                        )
                            .listRowSeparator(.hidden)
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                if item.stock != .inStock {
                                    Button { vm.onSetStock(item, .inStock) } label: {
                                        Label(Strings.pantryRestock, systemImage: "arrow.uturn.backward")
                                    }
                                    .tint(Palette.primary)
                                }
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                if item.stock != .runOut {
                                    Button { vm.onSetStock(item, .runOut) } label: {
                                        Label(Strings.pantryRanOut, systemImage: "xmark.circle")
                                    }
                                    .tint(Palette.muted)
                                }
                                if item.stock == .inStock {
                                    Button { vm.onSetStock(item, .runningLow) } label: {
                                        Label(Strings.pantryRunningLow, systemImage: "gauge.with.dots.needle.33percent")
                                    }
                                    .tint(Palette.accentText)
                                }
                            }
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
        .toolbar {
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
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(Strings.moreOptions)
                .tooltipAnchor(.pantryMenu, inToolbar: true)
            }
        }
        // The tooltips (#190); none over this screen's sheet and snackbar. Outside the toolbar,
        // whose menu is an anchor too.
        .tooltipHost(.pantry, blocked: state.editing != nil || state.message != nil)
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
        .modifier(SendFileEffect(vm: sendFileVM))
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

    /// Snackbars only for undo (#146).
    @ViewBuilder
    private func snackbar(_ message: PantryMessage) -> some View {
        switch message {
        case .deleted(_, let name):
            Snackbar(message: Strings.pantryDeleted(name), actionLabel: Strings.undo, action: vm.onUndoDelete)
        }
    }
}

private struct PantryRow: View {
    let item: PantryItem
    let today: Int64
    let onList: Bool
    let first: Bool
    let vm: PantryViewModel

    var body: some View {
        let stock = item.stock
        let others = PantryStock.allCases.filter { $0 != stock }
        let next: PantryStock = stock == .runOut ? .inStock : .runOut
        // VoiceOver gets both other states; the context menu only the one the button doesn't offer.
        let menuStates = others.filter { $0 != next }
        HStack(spacing: 8) {
            Button { vm.onEdit(item) } label: {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        Text(item.name)
                            .textStyle(Typography.bodyLarge)
                            .foregroundStyle(item.inStock ? Palette.onBackground : Palette.muted)
                        if stock == .runningLow {
                            Text(Strings.pantryLow)
                                .textStyle(Typography.labelSmall)
                                .foregroundStyle(Palette.accentText)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .overlay(Capsule().stroke(Palette.hairline, lineWidth: 1))
                                .accessibilityIdentifier("low-\(item.id)")
                        }
                    }
                    if !details.isEmpty {
                        Text(details.joined(separator: " · ")).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                    }
                    if let expiry {
                        Text(expiry.text)
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(expiry.badge != nil ? Palette.accentText : Palette.muted)
                            .accessibilityIdentifier("expiry-\(item.id)")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // VoiceOver reads the row as one ("Garlic, Run out, On list"), with the other two
            // states as its actions (#194).
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(description)
            .accessibilityAddTraits(.isButton)
            .accessibilityActions {
                ForEach(others, id: \.self) { choice in
                    Button(Self.action(choice)) { vm.onSetStock(item, choice) }
                }
            }
            if onList {
                // A state, not a message (#146): on the grocery list; tapping takes it off.
                Button { vm.onTakeOffList(item) } label: {
                    Text(Strings.pantryOnList)
                        .textStyle(Typography.bodySmall)
                        .foregroundStyle(Palette.accentText)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .overlay(Capsule().stroke(Palette.hairline, lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityHint(Strings.pantryTakeOffList)
                .accessibilityIdentifier("onList-\(item.id)")
            }
            // One labelled action instead of a switch (#194).
            Button(Self.action(next)) { vm.onSetStock(item, next) }
                .buttonStyle(TextActionStyle(color: Palette.accentText))
                .accessibilityLabel("\(Self.action(next)): \(item.name)")
                .accessibilityIdentifier("stockAction-\(item.id)")
                .modifier(FirstActionAnchor(first: first))
        }
        .padding(.vertical, 4)
        // The row's menu: the one state the button doesn't offer.
        .contextMenu {
            ForEach(menuStates, id: \.self) { choice in
                Button(Self.action(choice)) { vm.onSetStock(item, choice) }
            }
        }
        .accessibilityIdentifier("pantry-\(item.id)")
    }

    private var details: [String] {
        [item.quantity, item.alwaysHave ? Strings.pantryAlwaysHave : nil].compactMap { $0 }
    }

    private var expiry: (text: String, badge: ExpiryBadge?)? {
        guard let day = item.expiresDay else { return nil }
        let badge = PantryList.badge(day, today: today)
        return (badge == .expired ? Strings.pantryExpired : Strings.pantryUseBy(PantryDate.short(day)), badge)
    }

    private var description: String {
        ([item.name] + details + [Self.label(item.stock)] + [onList ? Strings.pantryOnList : nil, expiry?.text].compactMap { $0 })
            .joined(separator: ", ")
    }

    /// A stock state's name, as the row reads it out.
    static func label(_ stock: PantryStock) -> String {
        switch stock {
        case .inStock: return Strings.pantryInStock
        case .runningLow: return Strings.pantryRunningLow
        case .runOut: return Strings.pantryOut
        }
    }

    /// The action that moves an item to this state.
    static func action(_ stock: PantryStock) -> String {
        switch stock {
        case .inStock: return Strings.pantryRestock
        case .runningLow: return Strings.pantryRunningLow
        case .runOut: return Strings.pantryRanOut
        }
    }
}

private extension PantrySection {
    /// A stable id for the section's row in the List.
    var key: String { runOut ? "runOut" : "aisle-\(aisle?.key ?? "expiry")" }
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

/// The first row's Ran out / Restock, which the stock tooltip (#190) points at.
private struct FirstActionAnchor: ViewModifier {
    let first: Bool

    func body(content: Content) -> some View {
        if first { content.tooltipAnchor(.pantryInStock) } else { content }
    }
}
