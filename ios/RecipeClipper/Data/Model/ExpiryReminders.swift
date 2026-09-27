import Foundation

/// Which sentence a reminder says: what expires on its day, the next day, or both.
enum ExpiryReminderText: Equatable {
    case today, tomorrow, todayAndTomorrow
}

/// One morning's pantry reminder (#52): on `day` (an epoch day) at `ExpiryReminders.hour`, the
/// in-stock items expiring that day (`today`) and the day after (`tomorrow`), each A–Z.
/// Android's `ExpiryReminder`.
struct ExpiryReminder: Equatable {
    let day: Int64
    let today: [String]
    let tomorrow: [String]

    var text: ExpiryReminderText {
        if today.isEmpty { return .tomorrow }
        if tomorrow.isEmpty { return .today }
        return .todayAndTomorrow
    }
}

/// When the pantry's expiry reminders are due, and what they list (#52). Pure: the platforms
/// schedule what `plan` returns (Android one alarm for the first, iOS every one) and word it.
///
/// One reminder per morning at most, never one per item. Only items in stock, not a staple
/// ("Always have") and with a use-by date count.
enum ExpiryReminders {
    /// The reminder's local time: 9:00.
    static let hour = 9

    /// At most this many are scheduled ahead: iOS keeps only 64 pending notifications per app,
    /// which step timers share. A month of mornings is plenty; the next plan extends it.
    static let maxReminders = 30

    static func counts(_ item: PantryItem) -> Bool {
        item.inStock && !item.alwaysHave && item.expiresDay != nil
    }

    /// The reminder for the morning of `day`, or nil when nothing expires that day or the next.
    static func forDay(_ items: [PantryItem], day: Int64) -> ExpiryReminder? {
        let counted = items.filter(counts)
        let today = names(counted.filter { $0.expiresDay == day })
        let tomorrow = names(counted.filter { $0.expiresDay == day + 1 })
        if today.isEmpty && tomorrow.isEmpty { return nil }
        return ExpiryReminder(day: day, today: today, tomorrow: tomorrow)
    }

    /// Every reminder still to come, soonest first: `today`'s only while `minuteOfDay` (minutes
    /// since local midnight) is before `hour`, then each later morning with something expiring
    /// that day or the next. An item already past its date has no reminder left.
    static func plan(_ items: [PantryItem], today: Int64, minuteOfDay: Int) -> [ExpiryReminder] {
        let first = minuteOfDay < hour * 60 ? today : today + 1
        let days = Set(items.filter(counts).flatMap { item -> [Int64] in
            let expires = item.expiresDay!
            return [expires - 1, expires]
        }).filter { $0 >= first }.sorted()
        return days.prefix(maxReminders).compactMap { forDay(items, day: $0) }
    }

    /// "milk", "milk and eggs", "milk, eggs and yogurt": `and` is the translated "%1$@ and %2$@".
    static func joinNames(_ names: [String], and: (String, String) -> String) -> String {
        switch names.count {
        case 0: return ""
        case 1: return names[0]
        default: return and(names.dropLast().joined(separator: ", "), names.last!)
        }
    }

    /// The sentence as it opens a notification: its first letter in upper case.
    static func capitalized(_ sentence: String, locale: Locale) -> String {
        guard let first = sentence.first, first.isLowercase else { return sentence }
        return String(first).uppercased(with: locale) + sentence.dropFirst()
    }

    private static func names(_ items: [PantryItem]) -> [String] {
        var seen = Set<String>()
        let trimmed = items.map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { seen.insert($0).inserted }
        // Kotlin's compareBy(lowercase(ROOT), then itself): UTF-16 order, as `<` on String isn't.
        return trimmed.sorted { a, b in
            let la = Array(a.lowercased().utf16), lb = Array(b.lowercased().utf16)
            if la != lb { return la.lexicographicallyPrecedes(lb) }
            return Array(a.utf16).lexicographicallyPrecedes(Array(b.utf16))
        }
    }
}
