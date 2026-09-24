package com.example.recipeclipper.di

import android.content.Context
import androidx.room.Room
import com.example.recipeclipper.data.local.RecipeDatabase
import com.example.recipeclipper.data.local.dao.BackupDao
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.MealPlanDao
import com.example.recipeclipper.data.local.dao.RecipeDao
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
    fun backupDao(db: RecipeDatabase): BackupDao = db.backupDao()
}
