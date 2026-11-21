package com.example.recipeclipper

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.recipeclipper.data.AndroidPhotoStore
import com.example.recipeclipper.ui.common.StoredPhotoMapper
import java.io.File
import com.example.recipeclipper.data.AutoBackup
import com.example.recipeclipper.data.CookedPhotoRepository
import com.example.recipeclipper.data.ExpiryReminderCoordinator
import com.example.recipeclipper.data.MealReminderCoordinator
import com.example.recipeclipper.data.PlayBillingEntitlements
import com.example.recipeclipper.data.TimerCountdownSync
import com.example.recipeclipper.widgets.HomeWidgets
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class RecipeApp : Application(), ImageLoaderFactory {

    /** Coil's default loader, plus the recipe's own stored pictures (#386): `photo://<name>` is a file in the photo store. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(StoredPhotoMapper(File(filesDir, AndroidPhotoStore.DIR))) }
        .build()

    @Inject
    lateinit var expiryReminders: ExpiryReminderCoordinator

    @Inject
    lateinit var mealReminders: MealReminderCoordinator

    @Inject
    lateinit var billing: PlayBillingEntitlements

    @Inject
    lateinit var cookedPhotos: CookedPhotoRepository

    @Inject
    lateinit var autoBackup: AutoBackup

    @Inject
    lateinit var homeWidgets: HomeWidgets

    @Inject
    lateinit var processTextEntry: ProcessTextEntry

    @Inject
    lateinit var timerCountdowns: TimerCountdownSync

    override fun onCreate() {
        super.onCreate()
        // The pantry's expiry reminder (#52) follows the pantry and the setting for as
        // long as the process lives, and is planned afresh on every start.
        expiryReminders.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // The evening-before and Tonight reminders (#397): their alarms follow the settings and
        // the flag, and are armed afresh on every start.
        mealReminders.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // The unlock (#107): asks Play on every start, and tracks the Activity for its sheet.
        billing.start(this)
        // "I made this" (#116): files no photo names any more (a delete whose Undo never came,
        // an import's unused copies) go once the process is past them.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { cookedPhotos.sweep() }
        // The automatic backup copy (#150): a daily look for a copy to write.
        autoBackup.start()
        // The home-screen widgets (#309) follow the plan and the list while the process lives.
        homeWidgets.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // "Recipe Clipper" in other apps' selection menus (#306) follows the shareText flag (#334).
        processTextEntry.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // The countdowns outside the app (#309) go when `liveTimers` is turned off (#333).
        timerCountdowns.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
