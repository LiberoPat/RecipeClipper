import Foundation

/// What a home-screen widget (#309) has to show.
enum WidgetRows<Row: Equatable>: Equatable {
    /// The `widgets` flag is off: only the app's name.
    case off
    /// The plan or the list as it is. Only when it is read, and empty, does a widget say so.
    case rows([Row])
    /// The database couldn't be read: before the first unlock after a restart, or while the app
    /// migrates or imports. "Nothing planned" could be wrong then, so nothing is claimed, and
    /// the widget asks again within minutes (#329).
    case unavailable
}

/// A widget's timeline as plain values, which `HomeWidgetProvider` hands to WidgetKit.
struct WidgetTimelinePlan<Row: Equatable>: Equatable {
    struct Entry: Equatable {
        let date: Date
        let rows: WidgetRows<Row>
    }

    enum Reload: Equatable {
        /// Only when the app asks (`WidgetRefresher`).
        case never
        /// Once the last entry is the one showing.
        case atEnd
        case after(Date)
    }

    let entries: [Entry]
    let reload: Reload

    fileprivate static func single(_ rows: WidgetRows<Row>, at date: Date, reload: Reload) -> Self {
        Self(entries: [Entry(date: date, rows: rows)], reload: reload)
    }
}

/// What the home-screen widgets (#309) read, straight from the App Group database the app and
/// the share extension share, through the app's own DAOs and `WidgetContent`. In the data
/// layer, not the widget extension, so the unit tests reach it.
struct WidgetData: Sendable {
    /// How soon a widget that couldn't read the database asks again.
    static let retry: TimeInterval = 5 * 60

    /// The `widgets` flag, as the app mirrors it for the extension (`DefaultsWidgetsSwitch`).
    let isOn: @Sendable () -> Bool
    let open: @Sendable () throws -> AppDatabase

    /// The App Group's database and flag: what the widget extension reads.
    static let shared = WidgetData(
        isOn: { DefaultsWidgetsSwitch(defaults: UserDefaults(suiteName: AppGroup.identifier) ?? .standard).isOn },
        open: {
            guard let path = AppDatabase.sharedPath() else { throw CocoaError(.fileNoSuchFile) }
            return try AppDatabase(path: path)
        }
    )

    /// Tonight's timeline: today's meals from `now` and tomorrow's from midnight, so the day
    /// turns on time even when WidgetKit is slow to ask again (Low Power Mode, a widget off
    /// screen). The next timeline is asked for once tomorrow's meals are showing.
    func tonight(now: Date, calendar: Calendar = .current) async -> WidgetTimelinePlan<WidgetContent.Meal> {
        guard isOn() else { return .single(.off, at: now, reload: .never) }
        let dates = [now, Self.nextMidnight(after: now, calendar: calendar)]
        let days = dates.map { PlanDays.today(millis: Int64($0.timeIntervalSince1970 * 1000), zone: calendar.timeZone) }
        do {
            let db = try open()
            let (meals, types) = try await db.read { conn -> ([PlannedMeal], [MealType]) in
                let dao = MealPlanDao(db: conn)
                let types = try dao.mealTypes().map {
                    MealType(id: $0.id, name: $0.name, builtInKey: $0.builtInKey, sortOrder: $0.sortOrder)
                }
                return (try dao.days(start: days[0], end: days[1]), types)
            }
            let entries = zip(dates, days).map { date, day in
                WidgetTimelinePlan.Entry(date: date, rows: .rows(WidgetContent.tonight(meals, types: types, today: day)))
            }
            return WidgetTimelinePlan(entries: entries, reload: .atEnd)
        } catch {
            return .single(.unavailable, at: now, reload: .after(now.addingTimeInterval(Self.retry)))
        }
    }

    /// The grocery list's timeline: its unticked lines, as the Groceries screen shows them.
    /// Nothing about it changes with the clock, so only the app asks for the next one.
    func groceries(now: Date) async -> WidgetTimelinePlan<String> {
        guard isOn() else { return .single(.off, at: now, reload: .never) }
        do {
            let db = try open()
            let lines = try await db.read { conn in
                WidgetContent.groceries(GroceryCombiner.sections(try GroceryDao(db: conn).items().map(\.domain)))
            }
            return .single(.rows(lines), at: now, reload: .never)
        } catch {
            return .single(.unavailable, at: now, reload: .after(now.addingTimeInterval(Self.retry)))
        }
    }

    /// When "Tonight" turns into tomorrow: the next local midnight.
    static func nextMidnight(after now: Date, calendar: Calendar = .current) -> Date {
        calendar.nextDate(after: now, matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime)
            ?? now.addingTimeInterval(3600)
    }
}
