package com.example.recipeclipper.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RecipeDatabase.MIGRATION_17_18] (#381) run on the JVM over a bare `lists` table, so its
 * numbering is checked on every build. `MigrationTest` (a device test) runs it from the exported
 * schema against the whole database.
 */
@RunWith(AndroidJUnit4::class)
class ListsRenumberMigrationTest {

    private fun open(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext<Context>())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE lists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                            "isBuiltIn INTEGER NOT NULL, isFavorites INTEGER NOT NULL, sortOrder INTEGER NOT NULL, " +
                            "createdAt INTEGER NOT NULL, uid TEXT NOT NULL DEFAULT '')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun SupportSQLiteDatabase.add(id: Int, name: String, builtIn: Int, order: Int) = execSQL(
        "INSERT INTO lists (id, name, isBuiltIn, isFavorites, sortOrder, createdAt) VALUES (?, ?, ?, ?, ?, 0)",
        arrayOf<Any>(id, name, builtIn, if (id == 1) 1 else 0, order)
    )

    private fun SupportSQLiteDatabase.shown(): List<Pair<String, Int>> =
        query("SELECT name, sortOrder FROM lists ORDER BY sortOrder ASC, id ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }

    @Test
    fun everyListIsNumberedInTheOrderItWasShown() {
        val db = open()
        // Seeded lists first by position, then user lists by position then id: user lists and
        // seeded ones share positions, as old upgrades left them.
        db.add(1, "Favorites", 1, 0)
        db.add(2, "Lunch", 1, 1)
        db.add(3, "Weeknights", 0, 2)
        db.add(4, "Breakfast", 1, 2)
        db.add(5, "Aaa", 0, 0)
        db.add(6, "Zzz", 0, 0)

        RecipeDatabase.MIGRATION_17_18.migrate(db)

        assertEquals(
            listOf("Favorites" to 0, "Lunch" to 1, "Breakfast" to 2, "Aaa" to 3, "Zzz" to 4, "Weeknights" to 5),
            db.shown()
        )
    }

    @Test
    fun anAlreadyNumberedDatabaseIsUnchanged() {
        val db = open()
        listOf("Favorites", "Lunch", "Dinner").forEachIndexed { i, name -> db.add(i + 1, name, 1, i) }

        RecipeDatabase.MIGRATION_17_18.migrate(db)

        assertEquals(listOf("Favorites" to 0, "Lunch" to 1, "Dinner" to 2), db.shown())
    }

    @Test
    fun noListsIsNoWork() {
        val db = open()

        RecipeDatabase.MIGRATION_17_18.migrate(db)

        assertEquals(emptyList<Pair<String, Int>>(), db.shown())
    }
}
