package com.example.recipeclipper.data.flags

/**
 * Every feature flag (#87), one constant per entry in `shared/flags.json`, which both apps
 * read. `FeatureFlagRegistryTest` fails if the two lists differ, or if a flag is no longer
 * referenced anywhere: retiring a flag deletes it here, in flags.json and its branches in one PR.
 */
enum class Flag(val key: String) {
    /** Chef mode (#100): the Settings switch for short steps written on the device. */
    CHEF_MODE("chefMode"),

    /** The free tier (#107): 50 recipes (20 with [ONE_COOKBOOK] off), and the one-time unlock for unlimited ones. */
    FREE_TIER("freeTier"),

    /** A recipe picked from the page's text by the on-device model when the page has no recipe data (#103). */
    LLM_EXTRACTION("llmExtraction"),

    /** Typed decisions by the on-device model: close pantry names, aisles (#104). */
    AI_DECISIONS("aiDecisions"),

    /** With [AI_DECISIONS], the count-bracket decision too (#104); off since #127, kept to re-measure. */
    AI_COUNT_BRACKETS("aiCountBrackets"),

    /** Reddit posts (#11): Reddit links go to the Reddit source rather than the blog one. */
    REDDIT("reddit"),

    /** Reading a Reddit post's photo with on-device text recognition, checked by the cook (#198),
     *  and scanning the cook's own photos the same way (#226). */
    PHOTO_TEXT("photoText"),

    /** Running cook-mode timers outside the app (#309): an ongoing notification, a Live Activity. */
    LIVE_TIMERS("liveTimers"),

    /** The home-screen widgets (#309): Tonight and Groceries. */
    WIDGETS("widgets"),
    /** Print or save a recipe as PDF, and a list as a cookbook PDF (#318). */
    PRINT("print"),
    /** Text shared in with no link (#306): split into a recipe and opened in the editor to check. */
    SHARE_TEXT("shareText"),
    /** One cookbook (#345): nothing is removed automatically; the free tier (50) opens a new recipe unsaved at the limit; Recipes' views strip. */
    ONE_COOKBOOK("oneCookbook"),
    /** "Use it up" (#305): the saved recipes that use a pantry item about to expire. */
    USE_IT_UP("useItUp"),
    /** "Ask an AI" (#351, #353): prompts that come back as schema.org Recipe JSON, from Home and the recipe's menu. */
    ASK_AI("askAi"),
    /** Meal-plan import (#352): a week of AI recipes shared in opens a review, kept and planned on their days in Week. */
    MEAL_PLAN_IMPORT("mealPlanImport"),
    /** Recipe versions (#354): an AI's change to a recipe is saved linked to its original. */
    RECIPE_VERSIONS("recipeVersions"),
    /** Shop for this week (#384): the Week menu's one item, opening What I need, in place of What I need and Add this week's ingredients. */
    SHOP_FOR_WEEK("shopForWeek"),
    /** Reorder lists (#381): drag the covers on the Lists screen into your own order, used wherever lists are shown. */
    REORDER_LISTS("reorderLists"),
    /** Home's Today card (#402): today's next uncooked planned meal, when nothing is being cooked. */
    HOME_TONIGHT("homeTonight"),
    /** Select all / Clear all at the top of the put-away and Update the pantry checklists (#406). */
    PANTRY_SELECT_ALL("pantrySelectAll"),
    /** Recipes' quick filter chips under search (#378): 30 min or less, Not cooked yet, Uses what's in my pantry. */
    RECIPE_FILTERS("recipeFilters"),
    /** A toggle on Recipes between the rows and a photo grid (#380). */
    RECIPES_GRID("recipesGrid"),
    /** A long-press menu on a recipe row (#376): Add to list, Add to plan, Share, Delete, Remove from this list. */
    ROW_ACTIONS("rowActions"),
    /** Selecting several recipes (#377): add to a list, remove from this list, delete with one Undo. */
    MULTI_SELECT("multiSelect"),
    /** Skip tonight (#398): Week's long-press menu and today's meals gain Skip: push the week, move to the next free day, or remove. */
    SKIP_TONIGHT("skipTonight"),
    /** The evening-before reminder of tomorrow's meals, and the optional "Tonight" one (#397). */
    EVENING_REMINDERS("eveningReminders"),
    /** Fresh food (#396): Shop for this week in two trips (Buy now, Buy later in the week), and the plan's "cook it earlier?" note. */
    FRESH_FOOD("freshFood"),
    /** Plan my week with choices (#392, #367): meals, a start day, and new recipes, the cook's own or a mix. */
    PLAN_MY_WEEK_PLUS("planMyWeekPlus"),
    /** Library first (#375): the Recipes tab opens the library, with Today's card above search, a + sheet of ways in and the Settings gear; Home is gone. */
    LIBRARY_FIRST("libraryFirst");

    companion object {
        fun forKey(key: String): Flag? = entries.firstOrNull { it.key == key }
    }
}
