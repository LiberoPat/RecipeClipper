package com.example.recipeclipper.di

import com.example.recipeclipper.data.BackupRepository
import com.example.recipeclipper.data.DefaultBackupRepository
import com.example.recipeclipper.data.DefaultShortStepRepository
import com.example.recipeclipper.data.ShortStepRepository
import com.example.recipeclipper.data.DefaultGroceryRepository
import com.example.recipeclipper.data.DefaultListRepository
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.DefaultMealPlanRepository
import com.example.recipeclipper.data.DefaultPantryRepository
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.SystemPlanCalendar
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.SharedPrefsAppPreferences
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.TourPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun recipeRepository(impl: DefaultRecipeRepository): RecipeRepository

    @Binds
    @Singleton
    abstract fun listRepository(impl: DefaultListRepository): ListRepository

    @Binds
    @Singleton
    abstract fun mealPlanRepository(impl: DefaultMealPlanRepository): MealPlanRepository

    @Binds
    @Singleton
    abstract fun groceryRepository(impl: DefaultGroceryRepository): GroceryRepository

    @Binds
    @Singleton
    abstract fun pantryRepository(impl: DefaultPantryRepository): PantryRepository

    @Binds
    abstract fun planCalendar(impl: SystemPlanCalendar): PlanCalendar

    @Binds
    @Singleton
    abstract fun backupRepository(impl: DefaultBackupRepository): BackupRepository

    @Binds
    @Singleton
    abstract fun shortStepRepository(impl: DefaultShortStepRepository): ShortStepRepository

    @Binds
    @Singleton
    abstract fun decisionRepository(
        impl: com.example.recipeclipper.data.DefaultDecisionRepository
    ): com.example.recipeclipper.data.DecisionRepository

    @Binds
    @Singleton
    abstract fun unitPreferences(impl: SharedPrefsAppPreferences): AppPreferences

    /** The first-run tour's state (#151), in the same file as the settings. */
    @Binds
    @Singleton
    abstract fun tourPreferences(impl: SharedPrefsAppPreferences): TourPreferences
}
