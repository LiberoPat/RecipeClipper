import Foundation

/// A week of the meal plan as an iCalendar file (RFC 5545), for "Share as calendar file" (#52;
/// Android's `MealPlanIcs`). Pure: meals in, text out; pinned to the Kotlin by the differential
/// corpus's `Ics` rows.
///
/// **Every meal is an all-day event** on its day: meal types have no times, so a timed event
/// would invent one. The summary is the meal type and the recipe title or the note ("Dinner ·
/// Chicken Adobo"). Events are `TRANSPARENT` (the day isn't shown as busy), and the UID is the
/// entry's stable uid, so sharing the same week again updates rather than duplicates.
enum MealPlanIcs {
    static let productId = "-//Recipe Clipper//Meal plan//EN"
    private static let crlf = "\r\n"
    /// RFC 5545's limit on a content line, in octets, before it is folded.
    private static let lineOctets = 75

    /// The calendar for `meals` (in plan order), named by `mealTypeNames`; `stampMillis` is
    /// when it was made (each event's DTSTAMP).
    static func calendar(_ meals: [PlannedMeal], mealTypeNames: [Int64: String], stampMillis: Int64) -> String {
        let stamp = timestamp(stampMillis)
        var lines = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:\(productId)",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
        ]
        for meal in meals {
            lines += [
                "BEGIN:VEVENT",
                contentLine("UID", uid(meal)),
                "DTSTAMP:\(stamp)",
                "DTSTART;VALUE=DATE:\(date(meal.day))",
                "DTEND;VALUE=DATE:\(date(meal.day + 1))",
                contentLine("SUMMARY", summary(meal, mealTypeName: mealTypeNames[meal.mealTypeId] ?? "")),
                "TRANSP:TRANSPARENT",
                "END:VEVENT",
            ]
        }
        lines.append("END:VCALENDAR")
        return lines.joined(separator: crlf) + crlf
    }

    /// "meal-plan-2026-09-21.ics", for the week starting on `weekStart`.
    static func fileName(weekStart: Int64) -> String {
        let c = PlanDays.civil(weekStart)
        return "meal-plan-\(pad(c.year, 4))-\(pad(c.month))-\(pad(c.day)).ics"
    }

    /// "Dinner · Chicken Adobo", or just the title or note if the type has no name.
    static func summary(_ meal: PlannedMeal, mealTypeName: String) -> String {
        [mealTypeName, meal.title ?? meal.note ?? ""]
            .map(trimmed)
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }

    /// `NAME:value`, the value escaped as TEXT and the line folded at 75 octets.
    static func contentLine(_ name: String, _ value: String) -> String { fold("\(name):\(escape(value))") }

    private static func uid(_ meal: PlannedMeal) -> String {
        (meal.uid.isEmpty ? "entry-\(meal.id)" : meal.uid) + "@recipe-clipper"
    }

    /// Kotlin's `trim()`: strips characters up to and including U+0020 from both ends.
    private static func trimmed(_ s: String) -> String {
        let scalars = s.unicodeScalars
        guard let start = scalars.firstIndex(where: { $0.value > 0x20 }),
              let end = scalars.lastIndex(where: { $0.value > 0x20 }) else { return "" }
        return String(scalars[start...end])
    }

    /// RFC 5545 TEXT: backslash, semicolon, comma and newline are escaped; a CR is dropped.
    private static func escape(_ text: String) -> String {
        var out = ""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\\": out += "\\\\"
            case ";": out += "\\;"
            case ",": out += "\\,"
            case "\n": out += "\\n"
            case "\r": break
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out
    }

    /// Splits `line` into lines of at most 75 UTF-8 octets, each continuation starting with a
    /// space (which counts toward its 75). Never inside a code point.
    private static func fold(_ line: String) -> String {
        var out = String.UnicodeScalarView()
        var octets = 0
        for scalar in line.unicodeScalars {
            let size = UTF8.width(scalar)
            if octets + size > lineOctets {
                out.append(contentsOf: "\r\n ".unicodeScalars)
                octets = 1
            }
            out.append(scalar)
            octets += size
        }
        return String(out)
    }

    /// Zero-padded ASCII digits.
    private static func pad(_ n: Int, _ width: Int = 2) -> String {
        let digits = String(n)
        return String(repeating: "0", count: max(0, width - digits.count)) + digits
    }

    /// 20260923
    private static func date(_ day: Int64) -> String {
        let c = PlanDays.civil(day)
        return pad(c.year, 4) + pad(c.month) + pad(c.day)
    }

    /// 20260923T142500Z, in UTC.
    private static func timestamp(_ millis: Int64) -> String {
        var day = millis / PlanDays.millisPerDay
        var rest = millis % PlanDays.millisPerDay
        if rest < 0 { rest += PlanDays.millisPerDay; day -= 1 }
        let seconds = Int(rest / 1000)
        return date(day) + "T" + pad(seconds / 3600) + pad(seconds / 60 % 60) + pad(seconds % 60) + "Z"
    }
}
