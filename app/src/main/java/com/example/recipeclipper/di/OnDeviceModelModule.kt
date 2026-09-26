package com.example.recipeclipper.di

import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.MlKitDecisionModel
import com.example.recipeclipper.data.MlKitStepShortener
import com.example.recipeclipper.data.StepShortener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The on-device model's Chef mode (#100) and typed decisions (#104), on their own so the
 * walkthrough videos (#106) can swap in stubs through Hilt's `@UninstallModules`: an emulator
 * has no on-device model.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OnDeviceModelModule {

    @Binds
    @Singleton
    abstract fun stepShortener(impl: MlKitStepShortener): StepShortener

    @Binds
    @Singleton
    abstract fun decisionModel(impl: MlKitDecisionModel): DecisionModel
}
