package com.example.recipeclipper.di

import com.example.recipeclipper.data.MlKitStepShortener
import com.example.recipeclipper.data.StepShortener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Chef mode's model (#100), on its own so the walkthrough videos (#106) can swap in a stub
 * through Hilt's `@UninstallModules`: an emulator has no on-device model.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChefModelModule {

    @Binds
    @Singleton
    abstract fun stepShortener(impl: MlKitStepShortener): StepShortener
}
