package com.example.recipeclipper.di

import android.content.Context
import androidx.room.Room
import com.example.recipeclipper.data.local.RecipeDatabase
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.local.dao.GroceryDao
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.MealPlanDao
import com.example.recipeclipper.data.local.dao.MenuDao
import com.example.recipeclipper.data.local.dao.PantryDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.dao.ShortStepDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): RecipeDatabase =
        Room.databaseBuilder(context, RecipeDatabase::class.java, RecipeDatabase.NAME)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            // Never fallbackToDestructiveMigration: every version bump gets a real migration.
            .addMigrations(*RecipeDatabase.ALL_MIGRATIONS)
            .build()

    @Provides
    fun recipeDao(db: RecipeDatabase): RecipeDao = db.recipeDao()

    @Provides
    fun listDao(db: RecipeDatabase): ListDao = db.listDao()

    @Provides
    fun mealPlanDao(db: RecipeDatabase): MealPlanDao = db.mealPlanDao()

    @Provides
    fun groceryDao(db: RecipeDatabase): GroceryDao = db.groceryDao()

    @Provides
    fun pantryDao(db: RecipeDatabase): PantryDao = db.pantryDao()

    @Provides
    fun menuDao(db: RecipeDatabase): MenuDao = db.menuDao()

    @Provides
    fun backupDao(db: RecipeDatabase): BackupDao = db.backupDao()

    @Provides
    fun shortStepDao(db: RecipeDatabase): ShortStepDao = db.shortStepDao()

    @Provides
    fun aiDecisionDao(db: RecipeDatabase): com.example.recipeclipper.data.local.dao.AiDecisionDao = db.aiDecisionDao()
}
