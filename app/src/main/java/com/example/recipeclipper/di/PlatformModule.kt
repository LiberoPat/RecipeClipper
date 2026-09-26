package com.example.recipeclipper.di

import com.example.recipeclipper.BuildConfig
import com.example.recipeclipper.data.AndroidAppInfo
import com.example.recipeclipper.data.MlKitPageRecipeExtractor
import com.example.recipeclipper.data.MlKitStepShortener
import com.example.recipeclipper.data.PageRecipeExtractor
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.data.flags.FeatureFlagStore
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.flags.SharedPrefsFeatureFlagStore
import com.example.recipeclipper.data.AndroidBackupFiles
import com.example.recipeclipper.data.AndroidConnectivity
import com.example.recipeclipper.data.AppInfo
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.AndroidErrorLog
import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.TimerAlarmScheduler
import com.example.recipeclipper.data.ExpiryReminderScheduler
import com.example.recipeclipper.reminders.AndroidExpiryReminderScheduler
import com.example.recipeclipper.timers.AndroidTimerAlarmScheduler
import com.example.recipeclipper.data.WebViewRenderedPageSource
import com.example.recipeclipper.data.remote.RenderedPageSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import com.example.recipeclipper.data.DefaultLibraryPolicy
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.LibraryPolicy
import com.example.recipeclipper.data.PlayBillingEntitlements
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

    @Binds
    @Singleton
    abstract fun timerAlarmScheduler(impl: AndroidTimerAlarmScheduler): TimerAlarmScheduler

    @Binds
    @Singleton
    abstract fun expiryReminderScheduler(impl: AndroidExpiryReminderScheduler): ExpiryReminderScheduler

    @Binds
    @Singleton
    abstract fun appInfo(impl: AndroidAppInfo): AppInfo

    @Binds
    @Singleton
    abstract fun renderedPageSource(impl: WebViewRenderedPageSource): RenderedPageSource

    @Binds
    @Singleton
    abstract fun featureFlagStore(impl: SharedPrefsFeatureFlagStore): FeatureFlagStore

    @Binds
    @Singleton
    abstract fun stepShortener(impl: MlKitStepShortener): StepShortener

    @Binds
    @Singleton
    abstract fun pageRecipeExtractor(impl: MlKitPageRecipeExtractor): PageRecipeExtractor

    @Binds
    @Singleton
    abstract fun entitlements(impl: PlayBillingEntitlements): Entitlements

    @Binds
    @Singleton
    abstract fun libraryPolicy(impl: DefaultLibraryPolicy): LibraryPolicy

    companion object {
        @Provides
        fun errorLog(): ErrorLog = AndroidErrorLog

        /** The flags (#87): flags.json's defaults for this build type, under the stored overrides. */
        @Provides
        @Singleton
        fun featureFlags(store: FeatureFlagStore): FeatureFlags =
            FeatureFlags(store, FlagRegistry.definitions, isDebug = BuildConfig.DEBUG)
    }
}
