package com.example.recipeclipper.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recipes written inside an article (#435) open in the import's place (or the clip's, past
 * Cloudflare's check), so leaving the editor without saving goes back where the share came from,
 * never to a screen that would open it again; from the chooser, Back returns to the chooser.
 */
@RunWith(AndroidJUnit4::class)
class ArticleRouteTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private val url = "https://example.com/a"

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.RECIPES) {
                composable(Routes.RECIPES) { Text("stub:recipes") }
                composable(Routes.IMPORT) { Text("stub:import") }
                composable(Routes.CLIP) { Text("stub:clip") }
                composable(Routes.EDIT_ARTICLE) { Text("stub:editor") }
                composable(Routes.ARTICLE_CHOICE) { Text("stub:chooser") }
            }
        }
        compose.waitForIdle()
    }

    private fun current() = nav.currentBackStackEntry?.destination?.route

    @Test fun oneOpensTheEditorAndBackLeavesForWhereTheShareCameFrom() {
        show()
        compose.runOnIdle {
            nav.navigate(Routes.import(url))
            nav.openArticle(url, 1, Routes.IMPORT)
        }
        compose.waitForIdle()
        assertEquals(Routes.EDIT_ARTICLE, current())
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(Routes.RECIPES, current())
    }

    @Test fun severalOpenTheChooserAndBackFromTheEditorReturnsToIt() {
        show()
        compose.runOnIdle {
            nav.navigate(Routes.import(url))
            nav.openArticle(url, 2, Routes.IMPORT)
            nav.navigate(Routes.editArticle(url, 1))
        }
        compose.waitForIdle()
        assertEquals(Routes.EDIT_ARTICLE, current())
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(Routes.ARTICLE_CHOICE, current())
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(Routes.RECIPES, current())
    }

    @Test fun pastTheCheckTheyOpenInTheClipsPlace() {
        show()
        compose.runOnIdle {
            nav.navigate(Routes.clip(url, check = true))
            nav.openArticle(url, 1, Routes.CLIP)
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(Routes.RECIPES, current())
    }
}
