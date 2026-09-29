package com.example.recipeclipper.di

import com.example.recipeclipper.data.MlKitPhotoTextReader
import com.example.recipeclipper.data.PhotoTextReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * "Read the photo"'s reader (#198), in a module of its own so a test can swap just it
 * (`@UninstallModules(PhotoTextModule::class)`), as the walkthrough of it does: an emulator has
 * no Play services model to read with.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PhotoTextModule {
    @Binds
    @Singleton
    abstract fun photoTextReader(impl: MlKitPhotoTextReader): PhotoTextReader
}
