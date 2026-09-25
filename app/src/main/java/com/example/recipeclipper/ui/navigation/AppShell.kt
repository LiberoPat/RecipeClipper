package com.example.recipeclipper.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navigation
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The bottom tabs (#47): Recipes · Week · Groceries · Pantry, in that order (the owner's call on
 * #47). Each tab is a nested graph in one NavHost; switching tabs saves the tab being left and
 * restores the one being opened, so every tab keeps its own back stack.
 */
enum class Tab(
    val route: String,
    val startRoute: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int
) {
    RECIPES("tab/recipes", Routes.HOME, R.string.tab_recipes, R.drawable.ic_tab_recipes),
    WEEK("tab/week", Routes.WEEK, R.string.tab_week, R.drawable.ic_tab_week),
    GROCERIES("tab/groceries", Routes.GROCERIES, R.string.tab_groceries, R.drawable.ic_tab_groceries),
    PANTRY("tab/pantry", Routes.PANTRY, R.string.tab_pantry, R.drawable.ic_tab_pantry)
}

/**
 * Where the bar shows. An allow-list, so a new destination is bar-less until it says otherwise:
 * the recipe reading view, cook mode (a boolean on that same screen) and the import route stay
 * full screen, so a recipe still opens on the recipe.
 */
private val tabBarRoutes = setOf(
    Routes.HOME, Routes.HISTORY, Routes.SETTINGS, Routes.DEVELOPER_SETTINGS, Routes.LISTS, Routes.LIST_DETAIL,
    Routes.WEEK, Routes.MEAL_TYPES, Routes.WHAT_I_NEED, Routes.GROCERIES, Routes.PANTRY
)

internal fun NavDestination?.showsTabBar(): Boolean = this?.route in tabBarRoutes

internal fun NavDestination?.tab(): Tab? =
    this?.hierarchy?.let { chain -> Tab.entries.firstOrNull { tab -> chain.any { it.route == tab.route } } }

/**
 * Opens [tab] with its own back stack restored. Choosing the tab already open goes back to its
 * first screen instead, as tab bars conventionally do.
 */
fun NavHostController.selectTab(tab: Tab) {
    if (currentDestination.tab() == tab) {
        popBackStack(tab.startRoute, inclusive = false)
        return
    }
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * A route from an intent (a shared link's import, or a timer notification's cook-mode open,
 * MainActivity's queue) always lands in Recipes: switch to that tab if another is open, then
 * navigate on top of whatever the Recipes stack held.
 */
fun NavHostController.openRoute(route: String, tabsEnabled: Boolean) {
    if (tabsEnabled && currentDestination.tab() != Tab.RECIPES) selectTab(Tab.RECIPES)
    navigate(route)
}

/**
 * The app's root. With [tabsEnabled] off (the `mealPlan` flag, #87, off by default until the
 * meal plan ships) it is [RecipeNavHost] alone, exactly the app as it was before
 * the shell. On, the same Recipes graph sits under the first tab of a bottom bar.
 *
 * [recipes] is the Recipes graph, [week] the Week tab's (#49), [groceries] the Groceries
 * tab's (#50) and [pantry] the Pantry tab's (#51); tests pass stand-ins, since the real screens need Hilt.
 */
@Composable
fun AppShell(
    navController: NavHostController,
    tabsEnabled: Boolean,
    recipes: NavGraphBuilder.(NavHostController) -> Unit = { recipesDestinations(it) },
    week: NavGraphBuilder.(NavHostController) -> Unit = { weekDestinations(it) },
    groceries: NavGraphBuilder.(NavHostController) -> Unit = { groceriesDestinations() },
    pantry: NavGraphBuilder.(NavHostController) -> Unit = { pantryDestinations() }
) {
    if (!tabsEnabled) {
        RecipeNavHost(navController, recipes)
        return
    }

    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination

    Scaffold(
        // Transparent: every screen paints its own ground. Insets are left to the screens and
        // the bar, as they were before the shell.
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (destination.showsTabBar()) {
                TabBar(selected = destination.tab(), onSelect = { navController.selectTab(it) })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            NavHost(navController = navController, startDestination = Tab.RECIPES.route) {
                navigation(route = Tab.RECIPES.route, startDestination = Routes.HOME) {
                    recipes(navController)
                }
                navigation(route = Tab.WEEK.route, startDestination = Routes.WEEK) {
                    week(navController)
                }
                navigation(route = Tab.GROCERIES.route, startDestination = Routes.GROCERIES) {
                    groceries(navController)
                }
                navigation(route = Tab.PANTRY.route, startDestination = Routes.PANTRY) {
                    pantry(navController)
                }
            }
        }
    }
}

/**
 * The bar in the app's own tokens: ground with a hairline above it, paprika for the open tab,
 * muted for the rest, and the warm surface container as the indicator. No Material purple.
 * Themed here rather than around the whole shell, so it is never composed while the recipe
 * screen (which may force dark for cook mode) owns the system bar colours.
 */
@Composable
private fun TabBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    RecipeClipperTheme {
        Column {
            Hairline()
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tonalElevation = 0.dp,
                modifier = Modifier.testTag("tabBar")
            ) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.tertiary,
                    selectedTextColor = MaterialTheme.colorScheme.tertiary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
                        colors = colors
                    )
                }
            }
        }
    }
}
