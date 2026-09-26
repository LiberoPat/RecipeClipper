package com.example.recipeclipper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.local.dao.GroceryDao
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.MenuDao
import com.example.recipeclipper.data.local.dao.MealPlanDao
import com.example.recipeclipper.data.local.dao.PantryDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.dao.ShortStepDao
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MealTypeEntity
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.MenuEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.local.entity.ShortStepEntity
import com.example.recipeclipper.data.local.entity.newUid
import com.example.recipeclipper.data.model.MealType

@Database(
    entities = [
        RecipeEntity::class, ListEntity::class, RecipeListCrossRef::class,
        MealTypeEntity::class, MealPlanEntryEntity::class, GroceryItemEntity::class,
        PantryItemEntity::class, MenuEntity::class, MenuEntryEntity::class, ShortStepEntity::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RecipeDatabase : RoomDatabase() {

    abstract fun recipeDao(): RecipeDao
    abstract fun listDao(): ListDao
    abstract fun backupDao(): BackupDao
    abstract fun mealPlanDao(): MealPlanDao
    abstract fun groceryDao(): GroceryDao
    abstract fun pantryDao(): PantryDao
    abstract fun menuDao(): MenuDao
    abstract fun shortStepDao(): ShortStepDao

    companion object {
        const val NAME = "recipe_clipper.db"

        /**
         * The lists seeded on first run. Only Favorites is protected from deletion, and it is
         * identified by its `isFavorites` column rather than by being first or by its name —
         * every one of these can be renamed.
         *
         * The rest are starting points, not fixtures: they can be deleted like any list the
         * user makes. They are still flagged `isBuiltIn`, which now means only "seeded, and
         * sorts before user-created lists" — it stopped meaning "undeletable" when Breakfast
         * and Snacks were added and the delete guard moved to `isFavorites`.
         *
         * Breakfast and Snacks are last rather than in meal order so that this list and
         * [MIGRATION_1_2] produce exactly the same ordering. A migration that renumbered the
         * existing four to slot Breakfast between Favorites and Lunch would have to shift
         * every user-created list out of the way too, which is a lot of moving parts for a
         * cosmetic gain.
         */
        private val BUILT_IN_LISTS =
            listOf("Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks")

        /** Seeds the built-in lists once, when the database file is first created. */
        val SeedBuiltInLists = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                BUILT_IN_LISTS.forEachIndexed { index, name ->
                    db.execSQL(
                        "INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt, uid) " +
                                "VALUES (?, 1, ?, ?, ?, ?)",
                        arrayOf<Any>(name, if (index == 0) 1 else 0, index, now, newUid())
                    )
                }
                // Created at the latest version, so MIGRATION_7_8's seeding never runs here.
                seedMealTypes(db, now)
            }
        }

        /**
         * The seeded meal types (#49), in the order the Week shows them: each key names one
         * whatever it is renamed to. Dinner is where new meals default and where a deleted
         * type's meals go.
         */
        private val BUILT_IN_MEAL_TYPES = listOf(
            "breakfast" to "Breakfast",
            "lunch" to "Lunch",
            MealType.DINNER to "Dinner",
            "snack" to "Snack"
        )

        private fun seedMealTypes(db: SupportSQLiteDatabase, now: Long) {
            BUILT_IN_MEAL_TYPES.forEachIndexed { index, (key, name) ->
                db.execSQL(
                    "INSERT INTO meal_types (name, builtInKey, sortOrder, updatedAt, uid) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any>(name, key, index, now, newUid())
                )
            }
        }

        /**
         * Adds Breakfast and Snacks to databases created before they existed.
         *
         * A data-only migration: version 1 and version 2 have identical table definitions, so
         * there is no schema change here at all. It exists because [SeedBuiltInLists] runs in
         * `onCreate` and therefore never again — without this, only fresh installs would get
         * the two new lists.
         *
         * They are appended after whatever is already there, so nothing is renumbered and any
         * list the user had already made keeps its place. Guarded by `isBuiltIn = 1` when
         * reading the high-water mark, since the point is to sit with the seeded block.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                val nextOrder = db.query(
                    "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM lists WHERE isBuiltIn = 1"
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

                listOf("Breakfast", "Snacks").forEachIndexed { index, name ->
                    db.execSQL(
                        "INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt) " +
                                "VALUES (?, 1, 0, ?, ?)",
                        arrayOf<Any>(name, nextOrder + index, now)
                    )
                }
            }
        }

        /**
         * Adds the personal note (issue #27). Nullable, no default: every existing recipe
         * simply has no note yet, and the entity declares no default either, so the migrated
         * table matches a freshly created one exactly.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN notes TEXT")
            }
        }

        /**
         * Gives every recipe and list a stable `uid` (issue #26): what an export file calls it,
         * so a list keeps its identity through a rename and a later import or sync (#53) can
         * recognise it. Existing rows are backfilled with random version-4 UUIDs, the same form
         * [newUid] makes; the `''` default exists only so the column can be added NOT NULL.
         * The same SQL is iOS's `addUids`.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("recipes", "lists")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE $table SET uid = $RANDOM_UUID_SQL")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_${table}_uid ON $table (uid)")
                }
            }
        }

        /** A random version-4 UUID, lowercase, evaluated afresh for every row. */
        private const val RANDOM_UUID_SQL =
            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "lower(hex(randomblob(6)))"

        /**
         * Adds the recipe's language tag (issue #14), which picks the words its lines are read
         * with. Nullable, no default: a recipe stored before has none and is detected from its
         * own words when shown, since the page's declared language can't be rebuilt from what
         * was stored. A re-share fills it in.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN language TEXT")
            }
        }

        /**
         * Adds saved cook progress and the chosen servings (issue #10). Both nullable with no
         * default, like [MIGRATION_2_3]: an existing recipe has no cook in progress and uses
         * its own yield.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN cookState TEXT")
                db.execSQL("ALTER TABLE recipes ADD COLUMN servingsTarget INTEGER")
            }
        }

        /**
         * Adds whose words a recipe is (#29): `contentOrigin` (PARSED, EDITED, CLIPPED or
         * MANUAL, by name) and `editedAt`. Every existing recipe was parsed from its link and
         * never edited, so PARSED and null. The same SQL is iOS's `addContentOrigin`.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN contentOrigin TEXT NOT NULL DEFAULT 'PARSED'")
                db.execSQL("ALTER TABLE recipes ADD COLUMN editedAt INTEGER")
            }
        }

        /**
         * The week meal plan (#49): `meal_types`, seeded with Breakfast, Lunch, Dinner and
         * Snack, and `meal_plan_entries`. Both new, so nothing existing changes. Each row has
         * a stable `uid` and an `updatedAt`, for export and a later sync (#53). The same SQL
         * is iOS's `addMealPlan`.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MEAL_PLAN_SQL.forEach(db::execSQL)
                seedMealTypes(db, System.currentTimeMillis())
            }
        }

        private val MEAL_PLAN_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `meal_types` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `builtInKey` TEXT, `sortOrder` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_types_uid` ON `meal_types` (`uid`)",
            "CREATE TABLE IF NOT EXISTS `meal_plan_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`day` INTEGER NOT NULL, `mealTypeId` INTEGER NOT NULL, `recipeId` INTEGER, " +
                "`servings` INTEGER, `note` TEXT, `sortOrder` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL, " +
                "FOREIGN KEY(`mealTypeId`) REFERENCES `meal_types`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`recipeId`) REFERENCES `recipes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_plan_entries_uid` ON `meal_plan_entries` (`uid`)",
            "CREATE INDEX IF NOT EXISTS `index_meal_plan_entries_day` ON `meal_plan_entries` (`day`)",
            "CREATE INDEX IF NOT EXISTS `index_meal_plan_entries_mealTypeId` ON `meal_plan_entries` (`mealTypeId`)",
            "CREATE INDEX IF NOT EXISTS `index_meal_plan_entries_recipeId` ON `meal_plan_entries` (`recipeId`)"
        )

        /**
         * The grocery list (#50): `grocery_items`, new, so nothing existing changes. One list
         * for now (`listId` 1); a recipe's items outlive it (SET NULL). Each row has a stable
         * `uid` and an `updatedAt`, for export and a later sync (#53). The same SQL is iOS's
         * `addGroceries`.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                GROCERY_SQL.forEach(db::execSQL)
            }
        }

        private val GROCERY_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `grocery_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`listId` INTEGER NOT NULL, `text` TEXT NOT NULL, `language` TEXT, `aisle` TEXT NOT NULL, " +
                "`checked` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `recipeId` INTEGER, " +
                "`plannedDay` INTEGER, `updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL, " +
                "FOREIGN KEY(`recipeId`) REFERENCES `recipes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_grocery_items_uid` ON `grocery_items` (`uid`)",
            "CREATE INDEX IF NOT EXISTS `index_grocery_items_listId` ON `grocery_items` (`listId`)",
            "CREATE INDEX IF NOT EXISTS `index_grocery_items_recipeId` ON `grocery_items` (`recipeId`)"
        )

        /**
         * The pantry (#51): `pantry_items`, a new table, so nothing existing changes. Each row
         * has a stable `uid` and an `updatedAt`, for export and a later sync (#53). The same SQL
         * is iOS's `addPantry`.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                PANTRY_SQL.forEach(db::execSQL)
            }
        }

        private val PANTRY_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `pantry_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `quantity` TEXT, `language` TEXT, `aisle` TEXT NOT NULL, " +
                "`inStock` INTEGER NOT NULL, `alwaysHave` INTEGER NOT NULL, `purchasedDay` INTEGER, " +
                "`expiresDay` INTEGER, `updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pantry_items_uid` ON `pantry_items` (`uid`)"
        )

        /**
         * Reusable weekly menus (#52): `menus` and `menu_entries`, both new, so nothing existing
         * changes. Each row has a stable `uid` and an `updatedAt`, for export and a later sync
         * (#53). The same SQL is iOS's `addMenus`.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MENU_SQL.forEach(db::execSQL)
            }
        }

        private val MENU_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `menus` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_menus_uid` ON `menus` (`uid`)",
            "CREATE TABLE IF NOT EXISTS `menu_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`menuId` INTEGER NOT NULL, `dayOffset` INTEGER NOT NULL, `mealTypeId` INTEGER NOT NULL, " +
                "`recipeId` INTEGER, `servings` INTEGER, `note` TEXT, `sortOrder` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL, " +
                "FOREIGN KEY(`menuId`) REFERENCES `menus`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`mealTypeId`) REFERENCES `meal_types`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`recipeId`) REFERENCES `recipes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_menu_entries_uid` ON `menu_entries` (`uid`)",
            "CREATE INDEX IF NOT EXISTS `index_menu_entries_menuId` ON `menu_entries` (`menuId`)",
            "CREATE INDEX IF NOT EXISTS `index_menu_entries_mealTypeId` ON `menu_entries` (`mealTypeId`)",
            "CREATE INDEX IF NOT EXISTS `index_menu_entries_recipeId` ON `menu_entries` (`recipeId`)"
        )

        /**
         * Chef mode's short steps (#100): one new table of derived data, so nothing existing
         * changes. The same SQL is iOS's `addShortSteps`.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SHORT_STEPS_SQL.forEach(db::execSQL)
            }
        }

        private val SHORT_STEPS_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `short_steps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipeId` INTEGER NOT NULL, `stepHash` TEXT NOT NULL, `language` TEXT NOT NULL, " +
                "`shortText` TEXT, `updatedAt` INTEGER NOT NULL, `uid` TEXT NOT NULL, " +
                "FOREIGN KEY(`recipeId`) REFERENCES `recipes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_short_steps_uid` ON `short_steps` (`uid`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_short_steps_recipeId_stepHash_language` " +
                "ON `short_steps` (`recipeId`, `stepHash`, `language`)"
        )

        /** Every migration, in order: what the app and the tests open the database with. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
        )
    }
}
