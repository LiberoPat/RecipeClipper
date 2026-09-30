import SwiftUI

/// The Pantry tab (#51; Android's PantryScreen): "Add to the pantry", a search field, then
/// everything by aisle (or by expiry, from the menu), and what has run out last (#194). Each row
/// shows whether it's in stock, running low or run out, and its quantity as written; tapping the
/// row opens its edit sheet (stock, quantity, staple, use-by date, delete), with swipes and a
/// touch-and-hold menu as shortcuts. The menu sends what's in stock, as plain text or as a file (#149). A `List`, for the
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

    /// Snackbars only for undo (#146).
    @ViewBuilder
    private func snackbar(_ message: PantryMessage) -> some View {
        switch message {
        case .deleted(_, let name):
            Snackbar(message: Strings.pantryDeleted(name), actionLabel: Strings.undo, action: vm.onUndoDelete)
        }
    }
}

/// One item (#194): its name, a "Low" tag while running low, the basket "Groceries" tag while its
/// name is on the grocery list, and its quantity as written on the right. Tap the row for the edit
/// sheet (its stock control changes the state); touch and hold for the other two states; swipes
/// are shortcuts. No per-row button (owner, 2026-09-29: redundant beside tapping the item).
private struct PantryRow: View {
    let item: PantryItem
    let today: Int64
    let onList: Bool
    let first: Bool
    let vm: PantryViewModel

    /// VoiceOver's actions and the context menu: both other states.
    private var others: [PantryStock] { PantryStock.allCases.filter { $0 != item.stock } }

    var body: some View {
        let stock = item.stock
        Button { vm.onEdit(item) } label: {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    // The name, then its tags; they wrap under a long name or large text rather
                    // than squeezing it.
                    TagFlow {
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
                        if onList {
                            OnListTag()
                                .anchorPreference(key: OnListTagBounds.self, value: .bounds) { $0 }
                        }
                    }
                    if item.alwaysHave {
                        Text(Strings.pantryAlwaysHave).textStyle(Typography.bodySmall).foregroundStyle(Palette.muted)
                    }
                    if let expiry {
                        Text(expiry.text)
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(expiry.badge != nil ? Palette.accentText : Palette.muted)
                            .accessibilityIdentifier("expiry-\(item.id)")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // The quantity as written, in the button's old place; a long one is cut short.
                if let quantity = item.quantity {
                    Text(quantity)
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.muted)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .accessibilityIdentifier("quantity-\(item.id)")
                }
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // VoiceOver reads the row as one ("Garlic, 1 head, Run out, On your grocery list"), with
        // the other two states as its actions (#194).
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
        .accessibilityAddTraits(.isButton)
        .accessibilityActions {
            ForEach(others, id: \.self) { choice in
                Button(Self.action(choice)) { vm.onSetStock(item, choice) }
            }
        }
        .accessibilityIdentifier("pantry-\(item.id)")
        .modifier(FirstRowAnchor(first: first))
        // The tag is drawn inside the row's label, where it wraps with the name; its own button
        // lies over it, so tapping it takes the item off the list rather than opening the sheet.
        .overlayPreferenceValue(OnListTagBounds.self) { anchor in
            if let anchor {
                GeometryReader { proxy in
                    let rect = proxy[anchor]
                    Button { vm.onTakeOffList(item) } label: {
                        Color.clear.contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
                    .accessibilityLabel(Strings.pantryOnList)
                    .accessibilityHint(Strings.pantryTakeOffList)
                    .accessibilityIdentifier("onList-\(item.id)")
                }
            }
        }
        // The row's menu: both other states.
        .contextMenu {
            ForEach(others, id: \.self) { choice in
                Button(Self.action(choice)) { vm.onSetStock(item, choice) }
            }
        }
    }

    private var expiry: (text: String, badge: ExpiryBadge?)? {
        guard let day = item.expiresDay else { return nil }
        let badge = PantryList.badge(day, today: today)
        return (badge == .expired ? Strings.pantryExpired : Strings.pantryUseBy(PantryDate.short(day)), badge)
    }

    private var description: String {
        ([item.name, item.quantity, item.alwaysHave ? Strings.pantryAlwaysHave : nil, Self.label(item.stock)]
            + [onList ? Strings.pantryOnList : nil, expiry?.text])
            .compactMap { $0 }
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

private struct PantryEditSheet: View {
    let editing: PantryEditing
    let vm: PantryViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeading(Strings.pantryEditTitle)
                // Every state, visibly (#194): the row's menu and swipes are shortcuts. Applied
                // at once, as they are.
                Picker(
                    Strings.pantryEditTitle,
                    selection: Binding(get: { vm.uiState.editing?.stock ?? editing.stock }, set: vm.onEditStock)
                ) {
                    ForEach(PantryStock.allCases, id: \.self) { choice in
                        Text(PantryRow.label(choice)).tag(choice)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .accessibilityIdentifier("pantryEditStock")
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

/// The first row, which the stock tooltip (#190) points at. The anchor is a background, so the
/// row's own view is the same whether it's first or not: an `if first` around the row changed
/// its identity when a swipe made it first, and the List left the swiped cell drawn in its old
/// place (#216).
private struct FirstRowAnchor: ViewModifier {
    let first: Bool

    func body(content: Content) -> some View {
        content.background {
            if first { Color.clear.tooltipAnchor(.pantryInStock) }
        }
    }
}

/// The basket "Groceries" tag (#146; owner, 2026-09-29: "On list" wasn't understood): the
/// Groceries tab's icon and label. Drawn only; the row lays its button over it. At accessibility
/// sizes only the basket shows, so the item's name keeps its room.
private struct OnListTag: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let iconOnly = dynamicTypeSize.isAccessibilitySize
        HStack(spacing: 4) {
            Image(systemName: "basket")
            if !iconOnly {
                Text(Strings.tabGroceries).lineLimit(1)
            }
        }
        .textStyle(Typography.bodySmall)
        .foregroundStyle(Palette.accentText)
        .padding(.horizontal, iconOnly ? 8 : 10)
        .padding(.vertical, 4)
        .overlay(Capsule().stroke(Palette.hairline, lineWidth: 1))
        .fixedSize()
    }
}

/// Where the row's basket tag was drawn, for the button laid over it.
private struct OnListTagBounds: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? { nil }

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

/// The name, then its tags, left to right, wrapping onto a new line when the next one doesn't
/// fit (Android's FlowRow); each line's items are centred on each other.
private struct TagFlow: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let lines = arrange(proposal.width ?? .infinity, subviews)
        let height = lines.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(lines.count - 1, 0))
        return CGSize(width: lines.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in arrange(bounds.width, subviews) {
            var x = bounds.minX
            for (index, size) in line.items {
                subviews[index].place(
                    at: CGPoint(x: x, y: y + (line.height - size.height) / 2),
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += line.height + lineSpacing
        }
    }

    private struct Line {
        var items: [(Int, CGSize)] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(_ maxWidth: CGFloat, _ subviews: Subviews) -> [Line] {
        var lines: [Line] = []
        var line = Line()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(ProposedViewSize(width: maxWidth, height: nil))
            if !line.items.isEmpty && line.width + spacing + size.width > maxWidth {
                lines.append(line)
                line = Line()
            }
            line.width += (line.items.isEmpty ? 0 : spacing) + size.width
            line.height = max(line.height, size.height)
            line.items.append((index, size))
        }
        if !line.items.isEmpty { lines.append(line) }
        return lines
    }
}
