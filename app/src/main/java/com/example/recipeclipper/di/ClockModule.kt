package com.example.recipeclipper.di

import com.example.recipeclipper.data.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The real wall clock. Tests provide their own [Clock] to line up with virtual time. */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    fun clock(): Clock = Clock { System.currentTimeMillis() }
}
