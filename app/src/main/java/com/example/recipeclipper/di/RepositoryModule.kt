package com.example.recipeclipper.di

import com.example.recipeclipper.data.DefaultListRepository
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.SharedPrefsAppPreferences
import com.example.recipeclipper.data.local.AppPreferences
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
    abstract fun unitPreferences(impl: SharedPrefsAppPreferences): AppPreferences
}
