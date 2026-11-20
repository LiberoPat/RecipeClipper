import Foundation

/// One To buy row that can wait for a second shop (#396; Android's `LaterRow`): `row` is the
/// week's ingredient, `group` the perishable group it matched, and `firstDay` the earliest day
/// (an epoch day) any of its lines is planned for.
struct LaterRow: Equatable {
    let row: NeedRow
    let group: PerishableGroup
    let firstDay: Int64
}

/// Shop for this week's To buy, in two trips (#396): `now` first, then `later`.
struct ShopTrips: Equatable {
    let now: [NeedRow]
    let later: [LaterRow]
}

/// Splits To buy into "Buy now" and "Buy later in the week" (#396; Android's `FreshShop`).
///
/// A row goes **later** only when both hold, and everything else is **now**:
///  - its ingredient matches the perishables table (`Perishables.groupOf`, read with the
///    recipe's own language), and
///  - **every** planned line of it is for a meal at least `Perishables.laterDays` days from
///    today. A line with no day, a name the app can't read, or one meal sooner keeps the whole
///    row in Buy now: nothing is guessed.
///
/// Quantities are never touched: the rows are the ones What I need already shows, in the same order.
enum FreshShop {

    static func split(_ buy: [NeedRow], today: Int64) -> ShopTrips {
        var now: [NeedRow] = []
        var later: [LaterRow] = []
        for row in buy {
            if let group = laterGroup(row, today: today) {
                later.append(LaterRow(row: row, group: group, firstDay: row.lines.compactMap(\.day).min() ?? today))
            } else {
                now.append(row)
            }
        }
        return ShopTrips(now: now, later: later)
    }

    private static func laterGroup(_ row: NeedRow, today: Int64) -> PerishableGroup? {
        guard let name = row.name, let first = row.lines.first else { return nil }
        for line in row.lines {
            guard let day = line.day, day - today >= Int64(Perishables.laterDays) else { return nil }
        }
        return Perishables.groupOf(name, words: LanguageWords.forTag(first.language))
    }
}

/// One planned meal as the plan nudge sees it (#396): `key` is whatever the caller swaps by (a
/// plan entry's id, a review row's index), `day` its epoch day, `perishable` the most delicate
/// perishable ingredient of its recipe (`Perishables.mostDelicate`), nil for a note or a recipe
/// with none.
struct FreshMeal: Equatable {
    let key: Int64
    let day: Int64
    let perishable: Perishable?
}

/// "`name`: keeps about `keeps` days once bought": the meal `key`, on `day`, has a perishable
/// ingredient and sits later than that keeps from the plan's first day; swapping it with the meal
/// `swapKey`, on `swapDay`, puts it back inside. `name` has a capital first letter.
struct FreshNudge: Equatable {
    let key: Int64
    let name: String
    let keeps: String
    let day: Int64
    let swapKey: Int64
    let swapDay: Int64
}

/// The plan nudge (#396; Android's `FreshPlan`): a quiet note when a perishable meal is planned
/// later than its group keeps, counted from the plan's first day, with the one swap that fixes it.
/// Pure; **it only says it**: the caller moves nothing unless the cook taps.
///
/// The first day is the earliest planned day that is not before `today`; a meal before today is
/// left out. A meal is **late** when its day is more than its group's `maxDays` after the first
/// day. Its swap partner is the earliest-day other meal (a recipe or a note, in the given order on a
/// tie) whose day is within `maxDays` of the first day, and that can itself take the later day: a
/// partner with a perishable of its own must keep that long. With no such partner there is no
/// nudge, since there would be nothing to tap.
enum FreshPlan {

    static func nudges(_ meals: [FreshMeal], today: Int64) -> [FreshNudge] {
        let live = meals.filter { $0.day >= today }
        guard let first = live.map(\.day).min() else { return [] }
        let byDay = live.enumerated().sorted { a, b in
            a.element.day != b.element.day ? a.element.day < b.element.day : a.offset < b.offset
        }.map(\.element)
        return live.compactMap { meal in
            guard let perishable = meal.perishable else { return nil }
            let keeps = Int64(perishable.group.maxDays)
            if meal.day - first <= keeps { return nil }
            guard let partner = byDay.first(where: { other in
                other.key != meal.key && other.day < meal.day && other.day - first <= keeps &&
                    (other.perishable.map { meal.day - first <= Int64($0.group.maxDays) } ?? true)
            }) else { return nil }
            return FreshNudge(
                key: meal.key,
                name: String(perishable.name.prefix(1)).uppercased() + perishable.name.dropFirst(),
                keeps: perishable.group.keeps,
                day: meal.day,
                swapKey: partner.key,
                swapDay: partner.day
            )
        }
    }
}
