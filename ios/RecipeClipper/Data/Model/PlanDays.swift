import Foundation

/// The meal plan's calendar (#49), as plain numbers (Android's `PlanDays`). A day is an **epoch
/// day**: whole days since 1970-01-01 on the user's local calendar, so a week is seven
/// consecutive integers. Stored as such in `meal_plan_entries.day`.
///
/// Days of the week use `Calendar.firstWeekday`'s numbering, which is also Android's
/// `java.util.Calendar`: 1 = Sunday … 7 = Saturday.
enum PlanDays {
    static let millisPerDay: Int64 = 86_400_000

    /// How many days the plan sheets offer: this week and next.
    static let sheetDays = 14

    /// The local calendar day containing `millis`, for a zone `offsetMillis` ahead of UTC.
    static func epochDay(millis: Int64, offsetMillis: Int64) -> Int64 {
        floorDiv(millis + offsetMillis, millisPerDay)
    }

    /// Today in `zone` at `millis`: what the cull rule and "This week" are measured from.
    static func today(millis: Int64, zone: TimeZone = .current) -> Int64 {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        return epochDay(millis: millis, offsetMillis: Int64(zone.secondsFromGMT(for: date)) * 1000)
    }

    /// 1 = Sunday … 7 = Saturday. Epoch day 0 was a Thursday.
    static func dayOfWeek(_ day: Int64) -> Int {
        Int(floorMod(day + 4, 7)) + 1
    }

    /// The first day of the week holding `day`, for a week that starts on `firstDayOfWeek`.
    static func weekStart(_ day: Int64, firstDayOfWeek: Int) -> Int64 {
        day - floorMod(Int64(dayOfWeek(day) - firstDayOfWeek), 7)
    }

    /// The seven days of the week starting on `start`.
    static func weekDays(_ start: Int64) -> [Int64] { (0..<7).map { start + Int64($0) } }

    /// Midnight UTC of `day`, for formatting it with a UTC calendar (never the local zone,
    /// which could land on the day before).
    static func utcDate(_ day: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(day * 86_400))
    }

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    private static func floorMod(_ a: Int64, _ b: Int64) -> Int64 {
        let m = a % b
        return m < 0 ? m + b : m
    }
}
