package com.example.recipeclipper.walkthrough

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.MlKitPhotoTextReader
import com.example.recipeclipper.data.PhotoTextReader
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.data.remote.RedditRecipeParser
import com.example.recipeclipper.data.remote.RoutingRecipeSource
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.di.PhotoTextModule
import com.example.recipeclipper.di.SourceModule
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Walkthroughs 06–10 and 27–30 (#106): the Recipes screen, amounts in steps, Chef mode with a stub
 * model (an emulator has none), the free tier, a recipe picked from the page text (seeded as
 * such: no model runs), Reddit posts (#11) and their photos (#198), in German and French too
 * (#208), and a scanned recipe card (#226). A pasted link opens a canned recipe, as iOS's
 * UI-test source does, so no clip depends on the network; a Reddit link is read by the real
 * [RedditRecipeParser] from a `shared/fixtures/reddit/` listing the recording script pushed, in
 * place of reddit.com's `.json` (which refuses an emulator with 403).
 */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class, SourceModule::class, PhotoTextModule::class)
class RecipesWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val source: RecipeSource = RoutingRecipeSource(
        blog = object : RecipeSource {
            override suspend fun fetch(url: String): ParseResult = ParseResult.Success(Recipe(
                name = "Stub Chicken Soup", image = null,
                ingredients = listOf("1 whole chicken", "2 carrots", "8 cups water"),
                instructions = listOf("Simmer everything for 1 hour.", "Season and serve."),
                prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = url
            ))
        },
        // The post's listing, as reddit.com's `.json` would send it, through the real parser.
        reddit = object : RecipeSource {
            override suspend fun fetch(url: String): ParseResult {
                val fixture = REDDIT_FIXTURES.entries.first { (id, _) -> "/comments/$id/" in url }.value
                return RedditRecipeParser.parse(String(deviceFile("/data/local/tmp/$fixture")), url)
            }

            override fun readsRenderedPage(url: String) = false
        }
    )

    @BindValue @JvmField
    val decisionModel: DecisionModel = WalkthroughSeed.decisionModel()

    /**
     * "Read the photo" (#198), OCR SIMULATED: an emulator has no Play services model, so the first
     * read answers a recipe card's lines ([cardLines]: one the recogniser was unsure of) and every
     * later read answers nothing, for the fallback.
     */
    @BindValue @JvmField
    val photoTextReader: PhotoTextReader = object : PhotoTextReader {
        private var reads = 0
        override suspend fun read(imageUrls: List<String>): PhotoTextResult =
            realReader?.read(imageUrls)
                ?: if (reads++ == 0) PhotoTextResult.Read(cardLines) else PhotoTextResult.Read(emptyList())
    }

    /** ML Kit itself, for clip 30 when the emulator's Play services can read (see [realOcr]). */
    private var realReader: PhotoTextReader? = null

    /** What the first photo read answers: clip 28's English card, clip 29's French one. */
    private var cardLines = CARD_LINES

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(
        support = ChefSupport.Available(setOf("en")),
        written = mutableMapOf(
            WalkthroughSeed.CHEF_STEP to WalkthroughSeed.CHEF_SHORT,
            WalkthroughSeed.WHISK to WalkthroughSeed.WHISK_SHORT
        )
    )

    private fun field(label: String) = hasSetTextAction() and hasText(label, substring = true)

    /** Home's "Recipes ›" row, below the fold: not the Recipes tab, which is Home itself (#152). */
    private fun openRecipes() = tapScrolling(hasText("Recipes") and hasText("›"))

    private fun typeARecipe() {
        tapDescription("Add a recipe")
        tap("Type a recipe")
        waitFor(field("Name"))
        compose.onAllNodes(field("Name"))[0].performTextInput("Weeknight Stew")
        pause(800)
        compose.onAllNodes(field("Ingredients, one per line"))[0].performTextInput("2 carrots\n1 lb stewing beef")
        pause(800)
        tap("Save", 2000)
    }

    @Test
    fun test06_recipesScreen() {
        start()
        openRecipes()
        menu("Name")
        typeARecipe()
        back()
        tapDescription("Add a recipe")
        tap("Paste a link")
        // The dialog's field, not the Recipes search field behind it.
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput("https://example.com/chicken-soup")
        pause(800)
        tap("Go", 3000)
    }

    @Test
    fun test07_amountsInSteps() {
        start("amountsInSteps")
        tapDescription("Settings")
        swipeUp()
        tap("Amounts in steps")
        back()
        tap("Weeknight Chili")
        swipeUp()
        pause(1000)
        tap("Start cooking", 2500)
    }

    @Test
    fun test08_chefModeStubModel() {
        start("chefMode")
        tapDescription("Settings")
        swipeUp()
        tap("Chef mode")
        back()
        tap("Sponge Cake")
        swipeUp()
        pause(1000)
        tap(WalkthroughSeed.CHEF_SHORT, 2500) // as written…
        tap(WalkthroughSeed.CHEF_STEP, 2000) // …and short again
        tap("Start cooking", 2000)
        tap("As written", 2500)
    }

    @Test
    fun test09_freeTier() {
        start("freeTier")
        openRecipes()
        waitFor(hasText("20 of 20 recipes"))
        pause(2000)
        typeARecipe()
        waitFor(hasText("Unlock"))
        pause(3000)
        tap("Cancel")
    }

    @Test
    fun test10_pageExtractionLine() {
        start("llmExtraction")
        tap("Grandma's Lentil Soup")
        waitFor(hasText("Picked from the page text", substring = true))
        pause(3000)
        swipeUp()
    }

    /**
     * Reddit posts (#11), read by the real parser from fixtures (see the class comment): a
     * self-post's recipe, an old recipe card written out in a comment, and a food photo whose
     * comments hold no recipe.
     */
    @Test
    fun test27_redditImport() {
        start("reddit")
        for (post in listOf(SELF_POST, CARD_POST, PHOTO_POST)) {
            waitFor(field("Recipe URL"))
            compose.onAllNodes(field("Recipe URL"))[0].performTextInput(post)
            pause(1000)
            tap("Go", 3500)
            if (post != PHOTO_POST) {
                swipeUp()
                pause(1500)
            }
            back()
        }
    }

    /**
     * Walkthrough 28, "Read the photo" (#198), OCR SIMULATED: an untranscribed recipe card post,
     * its lines read into "Check the recipe", the unsure line fixed, saved as a clip; then a photo
     * that reads nothing opens the editor to finish by hand.
     */
    @Test
    fun test28_readThePhoto() {
        start("reddit", "photoText")
        openPost(CARD_PHOTO_POST)
        tap("Read the photo", 2500)
        waitFor(hasText("Read from the photo", substring = true))
        pause(2500)
        swipeUp()
        pause(3000)
        // Fix the line the recogniser was unsure of.
        val box = compose.onAllNodes(field("Ingredients, one per line"))[0]
        val typed = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        box.performTextReplacement(typed.replace("1 cup rasins", "1 cup raisins"))
        pause(2000)
        tap("Save", 3000)
        waitFor(hasText("Clipped by you", substring = true))
        pause(2500)
        swipeUp()
        pause(2500)
        back()
        openPost(BLURRY_PHOTO_POST)
        tap("Read the photo", 2500)
        waitFor(hasText("Couldn't read a recipe from the photo", substring = true))
        pause(4000)
    }

    /**
     * Walkthrough 29, reading other languages (#208): a German Reddit post (the `de` language
     * fixture's, through the real parser) sorted under its German headings, scaled and shown in
     * Metric; then a French recipe card, OCR SIMULATED (the `fr` fixture's photo lines), whose
     * glued "2 c. à soupesucre" is flagged, fixed and saved.
     */
    @Test
    fun test29_readingOtherLanguages() {
        cardLines = FRENCH_CARD_LINES
        start("reddit", "photoText")
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput(GERMAN_POST)
        pause(1000)
        tap("Go", 2500)
        waitFor(hasText("Omas Pfannkuchen"))
        // Doubled, 4 to 8: whole amounts, where 5 would show "312 1/2 g Mehl".
        repeat(4) { tap(hasContentDescription("Increase servings"), 400) }
        pause(1000)
        tap("As written", 1000)
        tap("Metric", 2000)
        swipeUp()
        pause(2000)
        back()
        openPost(FRENCH_CARD_POST)
        tap("Read the photo", 2500)
        waitFor(hasText("Read from the photo", substring = true))
        pause(2000)
        swipeUp()
        pause(2500)
        // Fix the line with its unit run into the next word.
        val box = compose.onAllNodes(field("Ingredients, one per line"))[0]
        val typed = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        box.performTextReplacement(typed.replace("2 c. à soupesucre", "2 c. à soupe de sucre"))
        pause(2000)
        tap("Save", 2500)
        waitFor(hasText("Clipped by you", substring = true))
        pause(1500)
        swipeUp()
        pause(1000)
    }

    /**
     * Walkthrough 30, "Scan a recipe" (#226): Recipes + → Scan a recipe → Choose from library, the
     * fixture card's two sides handed back as the Photo Picker's answer (the picker can't be
     * driven, as in clip 13), "Check the recipe" with both pages on top, the name typed (none is
     * guessed), the flagged line fixed, Save; then Home's own "Scan a recipe". ML Kit reads the
     * pages when the emulator's Play services can ([realOcr]); otherwise it's OCR SIMULATED (clip
     * 28's card lines), which the test logs and the recording script puts in the clip's name.
     */
    @Test
    fun test30_scanARecipe() {
        val pages = listOf(scanPage("card-front"), scanPage("card-back"))
        realReader = realOcr(pages.first())
        Log.i("Walkthrough", if (realReader != null) "OCR REAL" else "OCR SIMULATED")
        start("photoText")
        openRecipes()
        tapDescription("Add a recipe")
        tap("Scan a recipe")
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != MediaStore.ACTION_PICK_IMAGES && intent.type?.startsWith("image/") != true) return null
                val uris = pages.map(Uri::parse)
                val data = Intent().apply {
                    clipData = ClipData.newRawUri(null, uris[0]).apply { addItem(ClipData.Item(uris[1])) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                return Instrumentation.ActivityResult(Activity.RESULT_OK, data)
            }
        }
        instrumentation.addMonitor(picker)
        try {
            tap("Choose from library", 2500)
        } finally {
            instrumentation.removeMonitor(picker)
        }
        waitFor(hasContentDescription("Page 2 of 2"))
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("Read from the photo", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        pause(3000)
        // No title is guessed: the cook names it.
        compose.onAllNodes(field("Name"))[0].performTextInput("Aunt June's Oatmeal Cookies")
        pause(1500)
        show("Check these lines", 3000)
        // Fix the lines flagged to check, and ML Kit's other slips on this card (the simulated
        // lines' "1 cup rasins"; the real reader's "2 eg9s", "11/2", "1 oup", and the back's
        // "Directíons" heading, which it read with an accent and so left among the ingredients).
        val box = compose.onAllNodes(field("Ingredients, one per line"))[0]
        val typed = box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        box.performTextReplacement(
            typed.lines().filterNot { it.trim().startsWith("Direct") }.joinToString("\n") { line ->
                if ("rasin" in line || "raisi" in line) {
                    "1 cup raisins"
                } else {
                    line.replace("11/2", "1 1/2").replace("eg9s", "eggs").replace("1 oup", "1 cup").replace("Cups", "cups")
                }
            }
        )
        pause(2500)
        tap("Save", 3000)
        waitFor(hasText("Aunt June's Oatmeal Cookies"))
        pause(2000)
        swipeUp()
        pause(2500)
        back()
        back()
        // Home, still scrolled down to its Recipes row: back up to the link field.
        waitFor(hasScrollAction())
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Scan a recipe"))
        pause(1000)
        tap("Scan a recipe", 2500)
        Espresso.pressBack()
        pause(1000)
    }

    /** A fixture page the recording script pushed, as a picture of the app's own (FileProvider). */
    private fun scanPage(name: String): String {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "camera/walkthrough-$name.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(deviceFile("/data/local/tmp/$name.jpg"))
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }

    /**
     * ML Kit's reader if it reads [page] here. Play services may first have to download the model
     * (a read asks it to), so NotReady is retried for up to two minutes. Null: simulate.
     */
    private fun realOcr(page: String): PhotoTextReader? {
        val reader = MlKitPhotoTextReader(instrumentation.targetContext)
        repeat(24) {
            when (val result = runBlocking { reader.read(listOf(page)) }) {
                is PhotoTextResult.Read -> return reader.takeIf { result.lines.isNotEmpty() }
                PhotoTextResult.NotReady -> Thread.sleep(5000)
                PhotoTextResult.Failed -> return null
            }
        }
        return null
    }

    private fun openPost(link: String) {
        waitFor(field("Recipe URL"))
        compose.onAllNodes(field("Recipe URL"))[0].performTextInput(link)
        pause(1000)
        tap("Go", 3500)
        waitFor(hasText("Read the photo"))
        pause(2000)
    }

    companion object {
        const val CARD_PHOTO_POST = "https://www.reddit.com/r/Old_Recipes/comments/1f7a2bc/aunt_junes_oatmeal_cookies_front_and_back_of_the_card/"
        const val BLURRY_PHOTO_POST = "https://www.reddit.com/r/Old_Recipes/comments/1f7a3cd/aunt_junes_oatmeal_cookies_blurry_photo/"

        /** What the card "reads" as: an unsure word on one line, as a recogniser gives it. */
        val CARD_LINES = listOf(
            "Aunt June's Oatmeal Cookies", "Ingredients", "1 cup butter", "1 cup brown sugar", "2 eggs",
            "1 1/2 cups flour", "1 tsp baking soda", "3 cups rolled oats"
        ).map { PhotoLine(it, 0.9f) } + PhotoLine("1 cup rasins", 0.3f) + listOf(
            "Directions", "1. Cream the butter and sugar, then beat in the eggs.",
            "2. Stir in the flour, soda, oats and raisins.", "3. Drop by spoonfuls and bake at 350°F for 10 minutes."
        ).map { PhotoLine(it, 0.9f) }

        const val GERMAN_POST = "https://www.reddit.com/r/Kochen/comments/1f8b4de/omas_pfannkuchen/"
        const val FRENCH_CARD_POST = "https://www.reddit.com/r/cuisine/comments/1f8c5fr/la_fiche_de_crepes_de_ma_grandmere/"

        /** The French card's lines, as `shared/fixtures/languages/fr.json`'s photo has them. */
        val FRENCH_CARD_LINES = listOf(
            "Crêpes", "INGRÉDIENTS", "250 g de farine", "4 œufs", "1/2 l de lait", "2 c. à soupesucre", "1 pincée de sel",
            "PRÉPARATION", "Mettre la farine dans un saladier.", "Ajouter les œufs puis le lait petit à petit.",
            "Laisser reposer 1 heure et cuire dans une poêle chaude."
        ).map { PhotoLine(it, 0.9f) }

        const val SELF_POST = "https://www.reddit.com/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/"
        const val CARD_POST = "https://www.reddit.com/r/Old_Recipes/comments/1f5c3de/grandmas_date_nut_bread_found_in_her_recipe_tin/"
        const val PHOTO_POST = "https://www.reddit.com/r/food/comments/1f6d4ef/homemade_sunday_lasagna/"

        /** A post's id and the fixture `scripts/record-walkthroughs-android.sh` pushes for it. */
        val REDDIT_FIXTURES = mapOf(
            "1f4b2cd" to "recipes-self-post.json",
            "1f5c3de" to "old-recipes-card-transcription.json",
            "1f6d4ef" to "food-photo-chatter.json",
            "1f7a2bc" to "old-recipes-card-untranscribed.json",
            "1f7a3cd" to "old-recipes-card-untranscribed.json",
            "1f8b4de" to "de-self-post.json",
            "1f8c5fr" to "fr-card-untranscribed.json"
        )
    }
}
