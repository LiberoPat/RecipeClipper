import Foundation

/// What the home-screen widgets (#309) read, straight from the App Group database the app and
/// the share extension share, through the app's own DAOs and `WidgetContent`. Nil means the
/// `widgets` flag is off (the app's mirror of it, `DefaultsWidgetsSwitch`): only the app's
/// name shows. A database that can't be opened reads as empty.
enum WidgetData {
    private static var isOn: Bool {
        DefaultsWidgetsSwitch(defaults: UserDefaults(suiteName: AppGroup.identifier) ?? .standard).isOn
    }

    private static func database() -> AppDatabase? {
        guard let path = AppDatabase.sharedPath() else { return nil }
        return try? AppDatabase(path: path)
    }

    /// Today's meals, in meal type order.
    static func tonight(now: Date) async -> [WidgetContent.Meal]? {
        guard isOn else { return nil }
        guard let db = database() else { return [] }
        let today = PlanDays.today(millis: Int64(now.timeIntervalSince1970 * 1000))
        return (try? await db.read { conn in
            let dao = MealPlanDao(db: conn)
            let types = try dao.mealTypes().map {
                MealType(id: $0.id, name: $0.name, builtInKey: $0.builtInKey, sortOrder: $0.sortOrder)
            }
            return WidgetContent.tonight(try dao.days(start: today, end: today), types: types, today: today)
        }) ?? []
    }

    /// The list's unticked lines, as the Groceries screen shows them.
    static func groceries() async -> [String]? {
        guard isOn else { return nil }
        guard let db = database() else { return [] }
        return (try? await db.read { conn in
            WidgetContent.groceries(GroceryCombiner.sections(try GroceryDao(db: conn).items().map(\.domain)))
        }) ?? []
    }

    /// When "Tonight" turns into tomorrow: the next midnight, when each widget redraws by itself.
    static func nextMidnight(after now: Date) -> Date {
        Calendar.current.nextDate(after: now, matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime)
            ?? now.addingTimeInterval(3600)
    }
}
