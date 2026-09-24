package com.example.recipeclipper.di

import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.RecipeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Remote sources. The Reddit source joins the blog one here in phase 4 (at which point the
 *  repository will want a qualifier per source, or a router in front of them). */
@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun recipeSource(connectivity: Connectivity): RecipeSource = BlogRecipeSource(connectivity)
}
