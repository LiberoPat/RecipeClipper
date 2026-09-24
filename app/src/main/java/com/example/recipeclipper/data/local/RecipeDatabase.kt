package com.example.recipeclipper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef

@Database(
    entities = [RecipeEntity::class, ListEntity::class, RecipeListCrossRef::class],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RecipeDatabase : RoomDatabase() {

    abstract fun recipeDao(): RecipeDao
    abstract fun listDao(): ListDao

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
                        "INSERT INTO lists (name, isBuiltIn, isFavorites, sortOrder, createdAt) " +
                                "VALUES (?, 1, ?, ?, ?)",
                        arrayOf<Any>(name, if (index == 0) 1 else 0, index, now)
                    )
                }
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
         * Adds saved cook progress and the chosen servings (issue #10). Both nullable with no
         * default, like [MIGRATION_2_3]: an existing recipe has no cook in progress and uses
         * its own yield.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN cookState TEXT")
                db.execSQL("ALTER TABLE recipes ADD COLUMN servingsTarget INTEGER")
            }
        }

        /** Every migration, in order: what the app and the tests open the database with. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
