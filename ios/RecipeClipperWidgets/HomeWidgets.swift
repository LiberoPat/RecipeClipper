import SwiftUI
import WidgetKit

/// One home-screen widget's content (#309): its rows, or nil when the `widgets` flag is off.
struct HomeWidgetEntry<Row>: TimelineEntry {
    let date: Date
    let rows: [Row]?
}

/// Reads the rows when WidgetKit asks, and asks again at midnight (Tonight's day changes); the
/// app asks for every other redraw (`WidgetRefresher`).
struct HomeWidgetProvider<Row>: TimelineProvider {
    let load: @Sendable (Date) async -> [Row]?

    func placeholder(in context: Context) -> HomeWidgetEntry<Row> { HomeWidgetEntry(date: Date(), rows: []) }

    func getSnapshot(in context: Context, completion: @escaping (HomeWidgetEntry<Row>) -> Void) {
        let now = Date()
        Task { completion(HomeWidgetEntry(date: now, rows: await load(now))) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HomeWidgetEntry<Row>>) -> Void) {
        let now = Date()
        Task {
            let entry = HomeWidgetEntry(date: now, rows: await load(now))
            completion(Timeline(entries: [entry], policy: .after(WidgetData.nextMidnight(after: now))))
        }
    }
}

/// Tonight (#309): today's planned meals, each under its meal type. A tap opens the Week.
struct TonightWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Tonight", provider: HomeWidgetProvider<WidgetContent.Meal>(load: { await WidgetData.tonight(now: $0) })) { entry in
            HomeWidgetCard(title: WidgetStrings.tonight, rows: entry.rows, empty: WidgetStrings.tonightEmpty, rowHeight: 40) { meal in
                VStack(alignment: .leading, spacing: 0) {
                    if !meal.mealType.isEmpty {
                        Text(meal.mealType).font(WidgetFont.karla(12, .regular)).foregroundStyle(Palette.muted)
                    }
                    Text(meal.title).font(WidgetFont.karla(15, .regular)).foregroundStyle(Palette.onBackground)
                }
            }
            .widgetURL(AppLinks.tab("week"))
        }
        .configurationDisplayName(WidgetStrings.tonight)
        .description(WidgetStrings.tonightDescription)
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// The grocery list (#309): what's left to buy, as the Groceries screen shows it. A tap opens
/// Groceries.
struct GroceriesWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Groceries", provider: HomeWidgetProvider<String>(load: { _ in await WidgetData.groceries() })) { entry in
            HomeWidgetCard(title: WidgetStrings.groceries, rows: entry.rows, empty: WidgetStrings.groceriesEmpty, rowHeight: 21) { line in
                Text(line).font(WidgetFont.karla(15, .regular)).foregroundStyle(Palette.onBackground)
            }
            .widgetURL(AppLinks.tab("groceries"))
        }
        .configurationDisplayName(WidgetStrings.groceries)
        .description(WidgetStrings.groceriesDescription)
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// The card both widgets share: the title in paprika, then as many rows as fit and "+N more",
/// or a muted line when there are none; only the app's name with the flag off.
private struct HomeWidgetCard<Row, RowView: View>: View {
    let title: String
    let rows: [Row]?
    let empty: String
    let rowHeight: CGFloat
    @ViewBuilder let row: (Row) -> RowView
    @Environment(\.widgetFamily) private var family

    private var fits: Int { max(1, Int((family == .systemLarge ? 300 : 110) / rowHeight)) }

    var body: some View {
        let all = rows ?? []
        let shown = all.count <= fits ? all : Array(all.prefix(fits - 1))
        VStack(alignment: .leading, spacing: 4) {
            Text(rows == nil ? "Recipe Clipper" : title)
                .font(WidgetFont.fraunces(17, .semibold))
                .foregroundStyle(Palette.accentText)
            ForEach(Array(shown.enumerated()), id: \.offset) { _, item in row(item).lineLimit(1) }
            if shown.count < all.count {
                Text(WidgetStrings.more(all.count - shown.count))
                    .font(WidgetFont.karla(14, .regular)).foregroundStyle(Palette.muted)
            }
            if rows != nil, all.isEmpty {
                Text(empty).font(WidgetFont.karla(14, .regular)).foregroundStyle(Palette.muted)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(Palette.background, for: .widget)
    }
}
