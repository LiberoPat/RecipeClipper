package com.example.recipeclipper.walkthrough

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.fake.FakeDecisionModel
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Walkthroughs 13, 17, 18, 20, 21 and 23 (#106): "I made this" (#116), the first-run tour (#151, #190),
 * Done shopping and the On list tag (#146), using up the pantry after cooking (#147), what
 * Settings says about Chef mode on a phone that can't run it (#144), and "Mark as cooked" (#173). The model supports nothing
 * here: the typed decisions stay out of these clips, and Chef mode's model is "unsupported"
 * (simulated, as an emulator has none).
 */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class)
class CookingWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val decisionModel: DecisionModel = FakeDecisionModel(languages = emptySet())

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(support = ChefSupport.Unsupported)

    /** A tap by semantics, for a control at the foot of the reading view that "Start cooking" can cover. */
    private fun press(matcher: SemanticsMatcher, pauseMs: Long = 1500) {
        waitFor(matcher)
        val node = compose.onAllNodes(matcher)[0]
        runCatching { node.performScrollTo() }
        node.performSemanticsAction(SemanticsActions.OnClick)
        pause(pauseMs)
    }

    /**
     * The photo "Choose from library" returns: the picture the recording script pushed
     * (`WALKTHROUGH_PHOTO`), handed back as the system Photo Picker would, since a test can't
     * drive the picker. Without it, a plain drawn plate.
     */
    private fun cannedPhotoResult(): Instrumentation.ActivityResult {
        val context = instrumentation.targetContext
        val bytes = deviceFile(WALKTHROUGH_PHOTO).takeIf { it.isNotEmpty() } ?: drawnPlate()
        val file = File(context.cacheDir, "camera/walkthrough.jpg").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val data = Intent().apply {
            this.data = uri
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Instrumentation.ActivityResult(Activity.RESULT_OK, data)
    }

    private fun drawnPlate(): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(0xE7, 0xE1, 0xD9))
            drawCircle(400f, 400f, 320f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawCircle(400f, 400f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9C, 0x5A, 0x2E) })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** "I made this" (#116): a photo from the library (canned, see [cannedPhotoResult]) and a note. */
    @Test
    fun test13_iMadeThis() {
        start("cookedPhotos")
        tapScrolling(hasText("Recipes") and !isSelectable()) // Home's row, not the tab
        tapScrolling("Chocolate Chip Cookies")
        repeat(3) { swipeUp() }
        press(hasText("I made this"))
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == MediaStore.ACTION_PICK_IMAGES || intent.type?.startsWith("image/") == true) {
                    cannedPhotoResult()
                } else {
                    null
                }
        }
        instrumentation.addMonitor(picker)
        try {
            tap("Choose from library", 2500)
        } finally {
            instrumentation.removeMonitor(picker)
        }
        // The photo just added opens for its note.
        val note = hasSetTextAction() and hasText("Add a short note")
        waitFor(note)
        compose.onAllNodes(note)[0].performTextInput("Cut the dough into gingerbread men for the kids.")
        pause(2000)
        tapDescription("Close")
        pause(2000)
        press(hasContentDescription("Your photo", substring = true), 3000)
        tapDescription("Close")
    }

    /**
     * The first-run tour (#151, #190): the sample recipe waiting on Home, and a tooltip on each
     * screen, one at a time, pointing at its control.
     */
    @Test
    fun test17_firstRunTour() {
        start("mealPlan", seeded = false, firstRun = true)
        waitFor(hasTestTag("tooltip-home_link"))
        pause(2500)
        tap("Got it", 1500)
        tap("Tomato and White Bean Soup", 2000)
        waitFor(hasTestTag("tooltip-recipe_servings"))
        pause(2500)
        tap("Got it", 1500)
    }

    /**
     * Done shopping (#146): ticked items, the put-away sheet (what the pantry tracks starts
     * ticked), and Undo; then running out of a pantry item puts it on the list, tagged "On list".
     */
    @Test
    fun test18_doneShoppingAndOnList() {
        start("mealPlan", kitchen = true)
        tap("Groceries")
        for (item in listOf("soy sauce", "garlic", "bay leaves")) tap(hasText(item, substring = true), 1000)
        pause(1000)
        tapTag("doneShopping")
        tap("bay leaves") // not in the pantry yet: ticked to go in
        pause(1000)
        tapTag("putAwayButton")
        waitFor(hasText("Undo"))
        pause(1000)
        tap("Undo", 2500)
        tap("Pantry", 2000)
        tapTag("stockAction-${pantryIds.getValue("onions")}") // Ran out: onto the list
        waitFor(hasText("On list"))
        pause(2500)
        tap("Groceries", 2500)
    }

    /**
     * Using up the pantry after cooking (#147): the ticked lines, at the end of cook mode, become
     * "3 lb → 1 lb" and a keep / running low / out question ([WalkthroughSeed.pantry]); one
     * confirm, one Undo.
     */
    @Test
    fun test20_pantryUseUpAfterCooking() {
        start("mealPlan", kitchen = true)
        tap("Chicken Adobo")
        for (line in listOf("2 lb chicken thighs", "1/2 cup soy sauce", "1/3 cup white vinegar")) {
            tap(hasText(line, substring = true), 1000)
        }
        pause(1000)
        tap("Start cooking", 2000)
        repeat(3) { tap("Done — next step", 1200) }
        tap("Done — finish", 3000)
        waitFor(hasText("Update the pantry"))
        pause(1500)
        tap(hasContentDescription("soy sauce: Running low"), 2000)
        tapTag("useUpButton")
        waitFor(hasText("Undo"))
        pause(2000)
        tap("Undo", 2000)
    }

    /**
     * "Mark as cooked" (#173): a cooking with no photo, "Cooked" in its place, and a note; closing
     * it offers the pantry's use-up sheet (#147) for every line ([WalkthroughSeed.pantry]).
     */
    @Test
    fun test23_markAsCooked() {
        start("mealPlan", "cookedPhotos", kitchen = true)
        tap("Chicken Adobo")
        repeat(3) { swipeUp() }
        press(hasText("I made this"))
        tap("Mark as cooked", 2500)
        val note = hasSetTextAction() and hasText("Add a short note")
        waitFor(note)
        compose.onAllNodes(note)[0].performTextInput("Doubled the garlic.")
        pause(2000)
        tapDescription("Close")
        waitFor(hasText("Update the pantry"))
        pause(3500)
        tapTag("useUpButton")
        waitFor(hasText("Undo"))
        pause(3000)
    }

    /** Chef mode on a phone that can't run the model (#144), the model's answer simulated. */
    @Test
    fun test21_chefModeUnsupportedSimulated() {
        start("chefMode")
        tapDescription("Settings")
        show("Needs a phone with Google's on-device AI", 3500)
    }

    companion object {
        /** Where `scripts/record-walkthroughs-android.sh` puts the photo "I made this" adds. */
        const val WALKTHROUGH_PHOTO = "/data/local/tmp/rc-walkthrough-photo.jpg"
    }
}
