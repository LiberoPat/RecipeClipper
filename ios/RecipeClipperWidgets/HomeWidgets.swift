import SwiftUI
import WidgetKit

/// One home-screen widget's content (#309) from `date` on.
struct HomeWidgetEntry<Row: Equatable>: TimelineEntry {
    let date: Date
    let rows: WidgetRows<Row>
}

/// Hands WidgetKit the timeline `WidgetData` plans: the entries, and when to ask again. The app
/// asks for a redraw whenever the plan, the list or the flag changes (`WidgetRefresher`).
struct HomeWidgetProvider<Row: Equatable>: TimelineProvider {
    let plan: @Sendable (Date) async -> WidgetTimelinePlan<Row>

    // Not yet read: nothing is claimed.
    func placeholder(in context: Context) -> HomeWidgetEntry<Row> { HomeWidgetEntry(date: Date(), rows: .unavailable) }

    func getSnapshot(in context: Context, completion: @escaping (HomeWidgetEntry<Row>) -> Void) {
        let now = Date()
        Task { completion(HomeWidgetEntry(date: now, rows: await plan(now).entries.first?.rows ?? .unavailable)) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HomeWidgetEntry<Row>>) -> Void) {
        let now = Date()
        Task {
            let planned = await plan(now)
            let policy: TimelineReloadPolicy
            switch planned.reload {
            case .never: policy = .never
            case .atEnd: policy = .atEnd
            case .after(let date): policy = .after(date)
            }
            completion(Timeline(entries: planned.entries.map { HomeWidgetEntry(date: $0.date, rows: $0.rows) }, policy: policy))
        }
    }
}

/// Tonight (#309): today's planned meals, each under its meal type. A tap opens the Week.
struct TonightWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Tonight", provider: HomeWidgetProvider(plan: { await WidgetData.shared.tonight(now: $0) })) { entry in
            HomeWidgetCard(title: WidgetStrings.tonight, rows: entry.rows, empty: WidgetStrings.tonightEmpty) { meal in
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
        StaticConfiguration(kind: "Groceries", provider: HomeWidgetProvider(plan: { await WidgetData.shared.groceries(now: $0) })) { entry in
            HomeWidgetCard(title: WidgetStrings.groceries, rows: entry.rows, empty: WidgetStrings.groceriesEmpty) { line in
                Text(line).font(WidgetFont.karla(15, .regular)).foregroundStyle(Palette.onBackground)
            }
            .widgetURL(AppLinks.tab("groceries"))
        }
        .configurationDisplayName(WidgetStrings.groceries)
        .description(WidgetStrings.groceriesDescription)
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// The card both widgets share: the title in paprika, then as many rows as fit and "+N more"
/// (`FittedRows`), or a muted line when there are none; only the app's name with the flag off,
/// and only the title while the database can't be read (#329: never "Nothing planned" unread).
private struct HomeWidgetCard<Row: Equatable, RowView: View>: View {
    let title: String
    let rows: WidgetRows<Row>
    let empty: String
    @ViewBuilder let row: (Row) -> RowView

    private var heading: some View {
        Text(rows == .off ? WidgetStrings.appName : title)
            .font(WidgetFont.fraunces(17, .semibold))
            .foregroundStyle(Palette.accentText)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
    }

    var body: some View {
        Group {
            switch rows {
            case .off, .unavailable:
                heading
            case .rows(let all) where all.isEmpty:
                VStack(alignment: .leading, spacing: 4) {
                    heading
                    Text(empty).font(WidgetFont.karla(14, .regular)).foregroundStyle(Palette.muted)
                        .minimumScaleFactor(0.5)
                }
            case .rows(let all):
                FittedRows(rows: all, header: { heading }, row: row) { count, beside in
                    Text(beside ? WidgetStrings.moreBeside(count) : WidgetStrings.more(count))
                        .font(WidgetFont.karla(14, .regular)).foregroundStyle(Palette.muted)
                }
            }
        }
        // No Spacer under the rows: it would be offered half the height they are.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .containerBackground(Palette.background, for: .widget)
    }
}
