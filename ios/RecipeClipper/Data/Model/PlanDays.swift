import Foundation

/// The meal plan's calendar (#49), as plain numbers (Android's `PlanDays`). A day is an **epoch
/// day**: whole days since 1970-01-01 on the user's local calendar, so a week is seven
/// consecutive integers. Stored as such in `meal_plan_entries.day`.
///
/// Days of the week use `Calendar.firstWeekday`'s numbering, which is also Android's
/// `java.util.Calendar`: 1 = Sunday … 7 = Saturday.
enum PlanDays {
    static let millisPerDay: Int64 = 86_400_000

    /// How many days the plan sheets offer: today and the next 13 (#232).
    static let sheetDays = 14

    /// The Week tab's weeks: seven-day blocks counted from today (#232).
    static let blockDays: Int64 = 7

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

    /// The first day of the seven-day block holding `day`, blocks counted from `today` (#232):
    /// today+7k … today+7k+6, so the week shown first starts today, whatever weekday that is.
    static func blockStart(_ day: Int64, today: Int64) -> Int64 {
        today + blockDays * floorDiv(day - today, blockDays)
    }

    /// A menu meal's `dayOffset` (#52): days after the locale's `firstDayOfWeek`, 0 to 6. Menus
    /// keep weekdays, so a meal saved from a block that starts on any day keeps its weekday;
    /// menus saved before #232 stored exactly this.
    static func menuOffset(_ day: Int64, firstDayOfWeek: Int) -> Int {
        Int(floorMod(Int64(dayOfWeek(day) - firstDayOfWeek), 7))
    }

    /// The day among the seven from `start` that falls on a menu meal's weekday (`menuOffset`).
    static func menuDay(start: Int64, offset: Int, firstDayOfWeek: Int) -> Int64 {
        start + floorMod(Int64(firstDayOfWeek + offset - dayOfWeek(start)), 7)
    }

    /// The seven days of the week starting on `start`.
    static func weekDays(_ start: Int64) -> [Int64] { (0..<7).map { start + Int64($0) } }

    // MARK: Months (#52's month view), on the proleptic Gregorian calendar, as plain integers
    // (Howard Hinnant's days_from_civil and civil_from_days; Android's `PlanDays`).

    /// The epoch day of `year`-`month`-`dayOfMonth`; `month` is 1…12.
    static func epochDay(year: Int, month: Int, dayOfMonth: Int) -> Int64 {
        let y = Int64(month <= 2 ? year - 1 : year)
        let era = floorDiv(y, 400)
        let yearOfEra = y - era * 400
        let m = Int64(month)
        let dayOfYear = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + Int64(dayOfMonth) - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /// The year, month (1…12) and day of the month of `day`.
    static func civil(_ day: Int64) -> (year: Int, month: Int, day: Int) {
        let z = day + 719_468
        let era = floorDiv(z, 146_097)
        let dayOfEra = z - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let mp = (5 * dayOfYear + 2) / 153
        let dayOfMonth = Int(dayOfYear - (153 * mp + 2) / 5 + 1)
        let month = Int(mp < 10 ? mp + 3 : mp - 9)
        let year = Int(yearOfEra + era * 400) + (month <= 2 ? 1 : 0)
        return (year, month, dayOfMonth)
    }

    /// The first day of the month holding `day`.
    static func monthStart(_ day: Int64) -> Int64 { day - Int64(civil(day).day) + 1 }

    /// The first day of the month `months` after (or before, if negative) the one holding `day`.
    static func addMonths(_ day: Int64, _ months: Int) -> Int64 {
        let (year, month, _) = civil(day)
        let index = Int64(year) * 12 + Int64(month - 1) + Int64(months)
        return epochDay(year: Int(floorDiv(index, 12)), month: Int(floorMod(index, 12)) + 1, dayOfMonth: 1)
    }

    /// The month grid holding `day`: whole weeks from the locale's `firstDayOfWeek`, from the
    /// week of the month's first day to the week of its last, so four to six rows of seven.
    static func monthGrid(_ day: Int64, firstDayOfWeek: Int) -> [Int64] {
        let first = weekStart(monthStart(day), firstDayOfWeek: firstDayOfWeek)
        let last = weekStart(addMonths(day, 1) - 1, firstDayOfWeek: firstDayOfWeek) + 6
        return Array(first...last)
    }

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
