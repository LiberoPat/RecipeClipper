import Foundation

/// Which shopping items the Week menu offers (#384; Android's WeekMenu). With `shopForWeek` the
/// menu has one, "Shop for this week", which opens What I need; with it off, the two it had:
/// What I need and "Add this week's ingredients". Both act on the week shown, so the month view
/// has neither. An item whose entry point the screen wasn't given (a nil callback) is left out.
enum WeekShoppingMenu {
    enum Item: Hashable {
        case shopForWeek
        case whatINeed
        case addWeekIngredients
    }

    static func items(shopForWeek: Bool, canOpenWhatINeed: Bool, canAddToGroceries: Bool, inMonth: Bool) -> [Item] {
        guard !inMonth else { return [] }
        if shopForWeek { return canOpenWhatINeed ? [.shopForWeek] : [] }
        var items: [Item] = []
        if canOpenWhatINeed { items.append(.whatINeed) }
        if canAddToGroceries { items.append(.addWeekIngredients) }
        return items
    }
}
