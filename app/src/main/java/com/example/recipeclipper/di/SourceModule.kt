package com.example.recipeclipper.di

import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.data.remote.RedditRecipeSource
import com.example.recipeclipper.data.remote.RoutingRecipeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Remote sources. The repository sees one [RecipeSource], which routes by host: Reddit
 *  links to [RedditRecipeSource], everything else to [BlogRecipeSource]. */
@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun recipeSource(connectivity: Connectivity): RecipeSource = RoutingRecipeSource(
        blog = BlogRecipeSource(connectivity),
        reddit = RedditRecipeSource(connectivity)
    )
}
