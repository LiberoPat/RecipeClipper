import SwiftUI

/// One item (#194): its name, a "Low" tag while running low, the basket "Groceries" tag while its
/// name is on the grocery list, and its quantity as written on the right. Tap the row for the edit
/// sheet (its stock control changes the state); touch and hold for the other two states; swipes
/// are shortcuts. No per-row button (owner, 2026-09-29: redundant beside tapping the item).
struct PantryRow: View {
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
