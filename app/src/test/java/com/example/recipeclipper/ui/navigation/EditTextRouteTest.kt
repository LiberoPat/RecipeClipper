package com.example.recipeclipper.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.remote.SharedText
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.EditRecipeViewModel
import com.example.recipeclipper.ui.edit.SharedTextInbox
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The text route (#306) through a NavHost, as MainActivity sends it (#334): the text waits in
 * [SharedTextInbox] and only its key rides in the route, so a long text in Japanese, which once
 * stopped the route from matching and crashed the app, opens the editor like any other.
 */
@RunWith(AndroidJUnit4::class)
class EditTextRouteTest {

    @get:Rule
    val compose = createComposeRule()

    private val inbox = SharedTextInbox()
    private lateinit var nav: NavHostController

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { Text("stub:home") }
                editTextDestination(nav) { entry ->
                    val key = entry.arguments?.getString(EditRecipeViewModel.SHARED_TEXT_ARG).orEmpty()
                    remember(key) {
                        EditRecipeViewModel(
                            SavedStateHandle(mapOf(EditRecipeViewModel.SHARED_TEXT_ARG to key)),
                            FakeRecipeRepository(), sorting = Dispatchers.Unconfined, sharedTexts = inbox
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("stub:home").assertIsDisplayed()
    }

    @Test
    fun aLongJapaneseTextOpensTheEditorThroughTheRoute() {
        // Well past where the encoded text once broke the route: 20,000 characters, three bytes each.
        val step = "鶏肉を醤油と砂糖で焼きます。みりんを大さじ2入れて、弱火で煮ます。"
        val text = "材料\n- 鶏肉 300 g\n- 醤油 大さじ2\n作り方\n1. " + step.repeat(20_000 / step.length) + "\n2. 盛り付ける。"
        val target = SharedText.route(text) as SharedText.Route.Editor
        val route = Routes.editText(inbox.offer(text, target.reading))
        assertTrue(route, route.length < 40)
        show()

        compose.runOnIdle { nav.navigate(route) }
        compose.waitForIdle()

        compose.onNodeWithText("Check the recipe").assertIsDisplayed()
        compose.onNodeWithText("盛り付ける。", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(Routes.EDIT_TEXT, nav.currentBackStackEntry?.destination?.route)
    }
}
