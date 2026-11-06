import SwiftUI

/// A home-screen widget's list (#309): its header, as many rows as fit the height this view is
/// given, then how many more there are. Compiled into the app as well as the widget extension,
/// so the unit tests can lay it out.
///
/// Nothing here assumes a row's height (#329). Each way of showing the list is offered to
/// `ViewThatFits`, which takes the first that fits as measured, at the text size in use
/// (Dynamic Type) and in the widget's real size on this phone. So a row or the count is never
/// clipped. In order:
///
/// 1. At the text size in use, each cut of the list (`WidgetContent.fit`), fullest first, down
///    to one row and "+N more".
/// 2. If even that is too high (large accessibility sizes in a small widget), the same one row
///    and count at each smaller text size in turn: text only as much smaller as it takes, and
///    never an item unnamed or a list that looks complete.
/// 3. Last, for a height with room for a single row: the first item with the count beside it.
struct FittedRows<Row, Header: View, RowView: View, More: View>: View {
    let rows: [Row]
    var spacing: CGFloat = 4
    @ViewBuilder let header: () -> Header
    @ViewBuilder let row: (Row) -> RowView
    /// "+N more" under the rows, or the shorter count that goes beside a row.
    @ViewBuilder let more: (_ count: Int, _ beside: Bool) -> More
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Never a wall of rows, however tall the widget (Android's `MAX_ROWS`); and room for two
    /// is the least asked for here, the single row being the last resort.
    private var most: Int { max(min(rows.count, 12), 2) }

    /// The text sizes under the one in use, largest first, down to the default.
    private var smaller: [DynamicTypeSize] {
        DynamicTypeSize.allCases.filter { $0 < typeSize && $0 >= .large }.reversed()
    }

    var body: some View {
        ViewThatFits(in: .vertical) {
            ForEach(Array(stride(from: most, to: 1, by: -1)), id: \.self) { room in
                list(WidgetContent.fit(count: rows.count, rows: room))
            }
            ForEach(smaller, id: \.self) { size in
                list(WidgetContent.fit(count: rows.count, rows: 2)).dynamicTypeSize(size)
            }
            list(WidgetContent.fit(count: rows.count, rows: 1)).dynamicTypeSize(min(typeSize, .large))
        }
    }

    private func list(_ fit: WidgetContent.Fit) -> some View {
        VStack(alignment: .leading, spacing: spacing) {
            header()
            ForEach(Array(rows.prefix(fit.shown).enumerated()), id: \.offset) { _, item in
                if fit.inline {
                    HStack(alignment: .lastTextBaseline, spacing: 8) {
                        row(item).lineLimit(1)
                        Spacer(minLength: 0)
                        more(fit.more, true).lineLimit(1).fixedSize().layoutPriority(1)
                    }
                } else {
                    row(item).lineLimit(1)
                }
            }
            if fit.more > 0, !fit.inline { more(fit.more, false).lineLimit(1) }
        }
    }
}
