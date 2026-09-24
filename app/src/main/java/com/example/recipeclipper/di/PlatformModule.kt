package com.example.recipeclipper.di

import com.example.recipeclipper.data.AndroidBackupFiles
import com.example.recipeclipper.data.AndroidConnectivity
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.AndroidErrorLog
import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.ErrorLog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Platform services the data layer and ViewModels reach only through an interface. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {

    @Binds
    @Singleton
    abstract fun connectivity(impl: AndroidConnectivity): Connectivity

    @Binds
    abstract fun backupFiles(impl: AndroidBackupFiles): BackupFiles

    companion object {
        @Provides
        fun errorLog(): ErrorLog = AndroidErrorLog
    }
}
