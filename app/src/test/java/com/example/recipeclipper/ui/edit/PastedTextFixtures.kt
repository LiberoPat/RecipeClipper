package com.example.recipeclipper.ui.edit

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.ui.groceries.ReceivedListInbox
import kotlinx.coroutines.Dispatchers

/** What a cook pastes (#372), and the router that reads it, as the tests of every paste entry share them. */
class PastedTextFixtures {

    val inbox = SharedTextInbox()
    val lists = ReceivedListInbox()
    val router = PastedTextRouter(FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true), inbox, lists)
        .apply { dispatcher = Dispatchers.Unconfined }

    companion object {
        private fun recipe(name: String, day: String?) =
            """{"@context": "https://schema.org", "@type": "Recipe", "name": "$name", """ +
                (day?.let { """"day": "$it", """ } ?: "") +
                """"recipeYield": "2 servings", "recipeIngredient": ["1 cup rice", "2 eggs"], """ +
                """"recipeInstructions": [{"@type": "HowToStep", "text": "Cook."}]}"""

        /** An AI's answer to "describe a recipe": one schema.org recipe in a fence, among prose. */
        val json = "Here you go:\n```json\n" + recipe("Lemon Pasta", null) + "\n```"

        /** An AI's answer to "plan my week": two recipes with days. */
        val plan = "[" + recipe("Fried rice", "2026-11-23") + ",\n" + recipe("Omelette", "2026-11-24") + "]"

        /** A recipe written out, as a chat or a note has it. */
        val prose = "Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake."
    }
}
