package com.example.recipeclipper.walkthrough

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionModel
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
import org.junit.Test

/**
 * Walkthroughs 06–10, 27 and 28 (#106): the Recipes screen, amounts in steps, Chef mode with a stub
 * model (an emulator has none), the free tier, a recipe picked from the page text (seeded as
 * such: no model runs), and Reddit posts (#11). A pasted link opens a canned recipe, as iOS's
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
     * read answers a recipe card's lines (one the recogniser was unsure of) and every later read
     * answers nothing, for the fallback.
     */
    @BindValue @JvmField
    val photoTextReader: PhotoTextReader = object : PhotoTextReader {
        private var reads = 0
        override suspend fun read(imageUrls: List<String>): PhotoTextResult =
            if (reads++ == 0) PhotoTextResult.Read(CARD_LINES) else PhotoTextResult.Read(emptyList())
    }

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

        const val SELF_POST = "https://www.reddit.com/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/"
        const val CARD_POST = "https://www.reddit.com/r/Old_Recipes/comments/1f5c3de/grandmas_date_nut_bread_found_in_her_recipe_tin/"
        const val PHOTO_POST = "https://www.reddit.com/r/food/comments/1f6d4ef/homemade_sunday_lasagna/"

        /** A post's id and the fixture `scripts/record-walkthroughs-android.sh` pushes for it. */
        val REDDIT_FIXTURES = mapOf(
            "1f4b2cd" to "recipes-self-post.json",
            "1f5c3de" to "old-recipes-card-transcription.json",
            "1f6d4ef" to "food-photo-chatter.json",
            "1f7a2bc" to "old-recipes-card-untranscribed.json",
            "1f7a3cd" to "old-recipes-card-untranscribed.json"
        )
    }
}
