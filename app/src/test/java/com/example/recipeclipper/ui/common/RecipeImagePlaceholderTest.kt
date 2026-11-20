package com.example.recipeclipper.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagValues
import com.example.recipeclipper.data.model.NoPhotoSource
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The no-photo image (#403): which of the monogram and the label is drawn is picked by the size it
 * is given, never the screen; it is decorative; and with `noPhotoImage` off the slot is as it was.
 */
@RunWith(AndroidJUnit4::class)
class RecipeImagePlaceholderTest {

    @get:Rule
    val compose = createComposeRule()

    private val lentils = NoPhotoSource(
        "uid-lentils", "Grandma's Lentil Soup", "en",
        listOf("1 cup red lentils, rinsed", "2 carrots, diced", "1 onion, chopped")
    )

    private fun show(
        width: Dp,
        height: Dp,
        source: NoPhotoSource = lentils,
        style: NoPhotoStyle = NoPhotoStyle.Auto,
        flagOn: Boolean = true,
        dark: Boolean = false
    ) {
        compose.setContent {
            val flags = if (flagOn) FlagValues(setOf(Flag.NO_PHOTO_IMAGE)) else FlagValues.ALL_OFF
            CompositionLocalProvider(LocalFlagValues provides flags) {
                RecipeClipperTheme(forceDark = dark) {
                    Box(Modifier.width(width).height(height)) {
                        RecipeImagePlaceholder(
                            source, Modifier.size(width, height), style,
                            whenOff = { Box(it.size(1.dp).then(Modifier)) }
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aThumbnailIsLettering() {
        show(64.dp, 64.dp)
        compose.onNodeWithTag("noPhoto.monogram").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("noPhoto.label").fetchSemanticsNodes().size)
    }

    @Test
    fun aCoverIsTheIngredientLabel() {
        show(160.dp, 160.dp)
        compose.onNodeWithTag("noPhoto.label").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("noPhoto.monogram").fetchSemanticsNodes().size)
    }

    @Test
    fun theThresholdIsTheShorterSide() {
        // 84 x 64 is still a thumbnail; 84 x 65 is not.
        show(84.dp, 64.dp)
        compose.onNodeWithTag("noPhoto.monogram").assertIsDisplayed()
    }

    @Test
    fun aBandIsTheLabelToo() {
        show(320.dp, 112.dp)
        compose.onNodeWithTag("noPhoto.label").assertIsDisplayed()
    }

    @Test
    fun aRecipeWithNoIngredientLinesFallsBackToItsLetter() {
        show(320.dp, 150.dp, source = NoPhotoSource("uid-x", "The Best Lasagna", "en"))
        compose.onNodeWithTag("noPhoto.monogram").assertIsDisplayed()
    }

    @Test
    fun aTitleWithNoLetterAndNoNamesIsAPlainTile() {
        show(64.dp, 64.dp, source = NoPhotoSource("uid-x", "12345", "en"))
        compose.onNodeWithTag("noPhoto.plain").assertIsDisplayed()
    }

    @Test
    fun theStyleCanBeAskedFor() {
        show(160.dp, 160.dp, style = NoPhotoStyle.Monogram)
        compose.onNodeWithTag("noPhoto.monogram").assertIsDisplayed()
    }

    @Test
    fun aLabelCanBeAskedForOnASmallSurface() {
        show(64.dp, 64.dp, style = NoPhotoStyle.Label)
        compose.onNodeWithTag("noPhoto.label").assertIsDisplayed()
    }

    @Test
    fun theOnInkTintsDrawInDarkToo() {
        show(160.dp, 160.dp, dark = true)
        compose.onNodeWithTag("noPhoto.label").assertIsDisplayed()
    }

    @Test
    fun itIsDecorative() {
        show(160.dp, 160.dp)
        // Its words are drawn but never announced: no text node, so a screen reader says what it
        // said before (the title beside it).
        assertEquals(0, compose.onAllNodesWithText("red lentils").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("G").fetchSemanticsNodes().size)
    }

    @Test
    fun withTheFlagOffTheSlotIsAsItWas() {
        show(64.dp, 64.dp, flagOn = false)
        assertEquals(0, compose.onAllNodesWithTag("noPhoto.monogram").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("noPhoto.label").fetchSemanticsNodes().size)
    }

    @Test
    fun aPhotoThatFailsToLoadFallsBackToThePlaceholder() {
        compose.setContent {
            CompositionLocalProvider(LocalFlagValues provides FlagValues(setOf(Flag.NO_PHOTO_IMAGE))) {
                RecipeClipperTheme {
                    RecipeImage(
                        model = "file:///no/such/photo.jpg",
                        recipe = lentils,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("noPhoto.monogram").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("noPhoto.monogram").assertIsDisplayed()
    }

    @Test
    fun aRecipeWithNoPhotoAtAllIsThePlaceholder() {
        compose.setContent {
            CompositionLocalProvider(LocalFlagValues provides FlagValues(setOf(Flag.NO_PHOTO_IMAGE))) {
                RecipeClipperTheme {
                    RecipeImage(model = null, recipe = lentils, contentDescription = null, modifier = Modifier.size(64.dp))
                }
            }
        }
        compose.onNodeWithTag("noPhoto.monogram").assertIsDisplayed()
    }

    @Test
    fun aStoredPhotoThatCannotBeReadFallsBackToThePlaceholder() {
        // A recipe's own stored picture (#386) is a photo:// model: with its file gone and no link
        // behind it, it fails like any other, and shows the designed image (#404).
        compose.setContent {
            CompositionLocalProvider(LocalFlagValues provides FlagValues(setOf(Flag.NO_PHOTO_IMAGE))) {
                RecipeClipperTheme {
                    RecipeImage(
                        model = "photo://missing.jpg",
                        recipe = lentils,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("noPhoto.monogram").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("noPhoto.monogram").assertIsDisplayed()
    }
}
