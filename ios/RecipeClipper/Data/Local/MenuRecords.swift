import Foundation

/// A reusable weekly menu (#52; Android's `MenuEntity`): a named copy of a week's meals, to add
/// to any later week. Its meals are `MenuEntryRecord`s. `uid` and `updatedAt` are for export
/// and a later sync (#53).
struct MenuRecord: Equatable {
    var id: Int64 = 0
    var name: String
    var updatedAt: Int64
    var uid: String = newUid()

    static let columns = "id, name, updatedAt, uid"

    init(id: Int64 = 0, name: String, updatedAt: Int64, uid: String = newUid()) {
        self.id = id
        self.name = name
        self.updatedAt = updatedAt
        self.uid = uid
    }

    init(row: SQLiteRow) {
        id = row.int64(0)
        name = row.string(1)
        updatedAt = row.int64(2)
        uid = row.string(3)
    }
}

/// One meal of a menu (#52; Android's `MenuEntryEntity`), `dayOffset` days (0 to 6) after the
/// week's first day, shaped like `MealPlanEntryRecord`: a recipe at `servings`, or a `note`.
/// Entries go with their menu and with their recipe (cascade).
struct MenuEntryRecord: Equatable {
    var id: Int64 = 0
    var menuId: Int64
    var dayOffset: Int
    var mealTypeId: Int64
    var recipeId: Int64?
    var servings: Int?
    var note: String?
    var sortOrder: Int
    var updatedAt: Int64
    var uid: String = newUid()

    static let columns = "id, menuId, dayOffset, mealTypeId, recipeId, servings, note, sortOrder, updatedAt, uid"

    init(
        id: Int64 = 0, menuId: Int64, dayOffset: Int, mealTypeId: Int64, recipeId: Int64?, servings: Int?,
        note: String?, sortOrder: Int, updatedAt: Int64, uid: String = newUid()
    ) {
        self.id = id
        self.menuId = menuId
        self.dayOffset = dayOffset
        self.mealTypeId = mealTypeId
        self.recipeId = recipeId
        self.servings = servings
        self.note = note
        self.sortOrder = sortOrder
        self.updatedAt = updatedAt
        self.uid = uid
    }

    init(row: SQLiteRow) {
        id = row.int64(0)
        menuId = row.int64(1)
        dayOffset = row.int(2)
        mealTypeId = row.int64(3)
        recipeId = row.isNull(4) ? nil : row.int64(4)
        servings = row.isNull(5) ? nil : row.int(5)
        note = row.optionalString(6)
        sortOrder = row.int(7)
        updatedAt = row.int64(8)
        uid = row.string(9)
    }
}
