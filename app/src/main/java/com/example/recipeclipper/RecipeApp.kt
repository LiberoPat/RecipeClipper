package com.example.recipeclipper

import android.app.Application
import com.example.recipeclipper.data.ExpiryReminderCoordinator
import com.example.recipeclipper.data.PlayBillingEntitlements
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class RecipeApp : Application() {

    @Inject
    lateinit var expiryReminders: ExpiryReminderCoordinator

    @Inject
    lateinit var billing: PlayBillingEntitlements

    override fun onCreate() {
        super.onCreate()
        // The pantry's expiry reminder (#52) follows the pantry, the setting and the flag for as
        // long as the process lives, and is planned afresh on every start.
        expiryReminders.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // The unlock (#107): asks Play on every start, and tracks the Activity for its sheet.
        billing.start(this)
    }
}
