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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.geometry.Offset
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
import java.time.LocalDate

/**
 * Walkthroughs 13, 17, 18, 20, 21 and 23–26 (#106): "I made this" (#116), the first-run tour (#151, #190),
 * Done shopping and the On list tag (#146), using up the pantry after cooking (#147), what
 * Settings says about Chef mode on a phone that can't run it (#144), "Mark as cooked" (#173), the
 * tooltips (#190), the pantry's three states (#194) and "onion" matching "onions" (#191). The model supports nothing
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
        waitFor(hasTestTag("tooltip-recipe_units"))
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

    /**
     * The tooltips (#190) on a fresh install: one per visit, Got it; a later visit shows the
     * screen's next one; Settings' "Show tips again" brings the first back.
     */
    @Test
    fun test24_tooltips() {
        start("mealPlan", seeded = false, firstRun = true)
        waitFor(hasTestTag("tooltip-home_link"))
        pause(2500)
        tap("Got it", 1500)
        tap("Tomato and White Bean Soup", 2000)
        waitFor(hasTestTag("tooltip-recipe_units"))
        pause(4000) // a longer text
        tap("Got it", 1500)
        back()
        waitFor(hasTestTag("tooltip-home_new_recipe")) // the later visit's next one
        pause(2500)
        tap("Got it", 1500)
        tapDescription("Settings")
        waitFor(hasTestTag("tooltip-settings_units"))
        pause(2500)
        tap("Got it", 1500)
        reveal(hasText("Show tips again"))
        press(hasText("Show tips again"), 2000) // by semantics: the row can sit under the tab bar
        back()
        waitFor(hasTestTag("tooltip-home_link"))
        pause(2500)
    }

    /**
     * The pantry's three states (#194, #199): Ran out moves a row to Run out and puts it on the
     * list; tapping a row opens its sheet, whose stock control sets Running low (the Low tag, and
     * on the list); Restock; the long-press menu, offering only what the row's button doesn't;
     * and a swipe each way.
     */
    @Test
    fun test25_pantryStates() {
        start("mealPlan", kitchen = true)
        tap("Pantry", 2000)
        val onions = pantryIds.getValue("onions")
        tapTag("stockAction-$onions") // Ran out: to Run out, and onto the list
        scrollTo("pantryList", "onList-$onions")
        pause(2000)
        val oil = pantryIds.getValue("olive oil")
        scrollTo("pantryList", "pantry-$oil")
        compose.onNode(hasTestTag("pantry-$oil")).performTouchInput { click(Offset(width * 0.3f, centerY)) }
        waitFor(hasTestTag("pantryEditStock"))
        pause(2000)
        tapTag("pantryEditStock-RUNNING_LOW", 2500) // applied at once
        press(hasTestTag("pantryEditSave"))
        scrollTo("pantryList", "pantry-$oil")
        waitFor(hasText("Low")) // the tag (merged into the row, so found by its text)
        waitFor(hasTestTag("onList-$oil"))
        pause(2500)
        val garlic = pantryIds.getValue("garlic")
        scrollTo("pantryList", "pantry-$garlic")
        tapTag("stockAction-$garlic", 2000) // Restock
        scrollTo("pantryList", "pantry-$oil")
        compose.onNode(hasTestTag("pantry-$oil")).performTouchInput { longClick(Offset(width * 0.3f, centerY)) }
        waitFor(hasTestTag("stockMenu-IN_STOCK")) // only Restock: the row's button is Ran out
        pause(2500)
        tapTag("stockMenu-IN_STOCK", 2000)
        swipeRow("basmati rice", left = true) // Ran out
        swipeRow("milk", left = false) // Restock
    }

    private fun swipeRow(name: String, left: Boolean) {
        val id = pantryIds.getValue(name)
        scrollTo("pantryList", "pantry-$id")
        pause(700)
        compose.onNode(hasTestTag("pantry-$id")).performTouchInput {
            if (left) swipeLeft(durationMillis = 700) else swipeRight(durationMillis = 700)
        }
        pause(2000)
    }

    /**
     * "onion" is "onions" (#191): with onions in the pantry, What I need puts the Adobo's
     * "1 onion, sliced" under In your pantry, while the Guacamole's red onion stays To buy.
     */
    @Test
    fun test26_onionAndOnions() {
        start("mealPlan", kitchen = true)
        tap("Week")
        planToday("Chicken Adobo")
        planToday("Guacamole")
        menu("What I need")
        show("red onion", 2500)
        show("onion, sliced", 3500)
    }

    private fun planToday(title: String) {
        val today = LocalDate.now().toEpochDay()
        scrollTo("weekList", "addToDay-$today")
        tapTag("addToDay-$today")
        type("addSearch", title.substringBefore(' '), submit = false)
        tap(title)
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
