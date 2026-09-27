package com.example.recipeclipper.di

import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FeatureFlags
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
 *  links to [RedditRecipeSource] (behind the `reddit` flag, #11), everything else to
 *  [BlogRecipeSource]. */
@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun recipeSource(connectivity: Connectivity, flags: FeatureFlags): RecipeSource = RoutingRecipeSource(
        blog = BlogRecipeSource(connectivity),
        reddit = RedditRecipeSource(connectivity),
        redditOn = { flags.isOn(Flag.REDDIT) }
    )
}
