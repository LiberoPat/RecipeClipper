package com.example.recipeclipper.walkthrough

import com.example.recipeclipper.data.model.DecisionKind
import com.example.recipeclipper.data.model.DecisionReply
import com.example.recipeclipper.fake.FakeDecisionModel

/**
 * The walkthrough videos' library (#106): the same twenty realistic recipes as iOS's
 * `UITestWalkthroughSeed`, viewed in this order, newest first, each in a list (so none is
 * removable and the free tier's "20 of 20" meets the library-full prompt). "Grandma's Lentil
 * Soup" is stored as picked from the page text (#103); the Sponge Cake's first two steps are
 * the ones the stub Chef mode model shortens (#100); the Banana Bread's [JUNK_LINE] ends in
 * junk (#132).
 */
object WalkthroughSeed {
    class Seed(
        val title: String,
        val slug: String,
        val servings: String,
        val ingredients: List<String>,
        val steps: List<String>,
        val origin: String = "PARSED",
        val times: Triple<String?, String?, String?> = Triple(null, null, null)
    )

    const val CHEF_STEP = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    const val CHEF_SHORT = "Preheat oven to 350°F; butter a 9-inch tin."
    const val WHISK = "Whisk the eggs and sugar in a large bowl until pale, thick and doubled in volume, about 8 minutes."
    const val WHISK_SHORT = "Whisk eggs and sugar in a bowl until pale and thick, about 8 minutes."

    /** A line ending in junk, as a site might publish it (#132): hidden in Groceries only. */
    const val JUNK_LINE = "2 eggs dfsafs"

    val recipes: List<Seed> = listOf(
        Seed("Chicken Adobo", "chicken-adobo", "4",
            listOf("2 lb chicken thighs", "1/2 cup soy sauce", "1/3 cup white vinegar", "6 cloves garlic, crushed",
                "1 onion, sliced", "3 bay leaves", "1 tsp black peppercorns"),
            listOf("Put the chicken, soy sauce, vinegar, garlic, bay leaves and peppercorns in a pot and marinate for 30 minutes.",
                "Bring to a boil, then simmer covered for 30 minutes.",
                "Add the onion and simmer uncovered for 10 minutes, until the sauce thickens.",
                "Serve with steamed rice."),
            times = Triple("35m", "40m", "1h 15m")),
        Seed("Weeknight Chili", "weeknight-chili", "6",
            listOf("1 lb beef", "1 onion, diced", "3 cloves garlic, minced", "2 tbsp chili powder",
                "1 can (14 oz) diced tomatoes", "1 can (15 oz) kidney beans, drained", "1 cup beef broth"),
            listOf("Brown the beef in a large pot.", "Add the onion and garlic and cook for 5 minutes.",
                "Stir in the chili powder, tomatoes, beans and broth.", "Simmer for 20 minutes.", "Serve with rice."),
            times = Triple("10m", "30m", "40m")),
        Seed("Spaghetti Carbonara", "spaghetti-carbonara", "4",
            listOf("400 g spaghetti", "150 g guanciale, diced", "4 egg yolks", "1 egg",
                "50 g pecorino romano, grated", "1 tsp black pepper"),
            listOf("Cook the spaghetti in salted water for 9 minutes.", "Fry the guanciale until crisp.",
                "Whisk the yolks, egg, pecorino and pepper.",
                "Toss the pasta with the guanciale off the heat, then stir in the eggs with a splash of pasta water.")),
        Seed("Sponge Cake", "sponge-cake", "8",
            listOf("4 eggs", "1 cup sugar", "1 cup flour", "1 tsp vanilla extract", "2 tbsp butter, melted"),
            listOf(CHEF_STEP, WHISK, "Fold in the flour in three additions, then the vanilla and the butter.",
                "Bake at 350°F for 25 minutes.", "Cool in the tin for 10 minutes before turning out.")),
        Seed("Grandma's Lentil Soup", "blog/lentil-soup-memories", "6",
            listOf("1 cup red lentils", "1 onion, chopped", "2 carrots, diced", "6 cups vegetable broth", "1 tsp cumin"),
            listOf("Soften the onion and carrots in a little oil.", "Add the lentils, broth and cumin.",
                "Simmer for 25 minutes, until the lentils fall apart."),
            origin = "EXTRACTED"),
        Seed("Banana Bread", "banana-bread", "1 loaf",
            listOf("3 ripe bananas", "2 cups flour", "1 tsp baking soda", "1/2 cup butter, melted",
                "3/4 cup brown sugar", JUNK_LINE),
            listOf("Mash the bananas and stir in the butter, sugar and eggs.", "Fold in the flour and baking soda.",
                "Bake at 350°F for 60 minutes.")),
        Seed("Miso Soup", "miso-soup", "4",
            listOf("4 cups dashi", "3 tbsp white miso paste", "1 block tofu, cubed", "2 green onions, sliced"),
            listOf("Warm the dashi.", "Whisk in the miso off the boil.", "Add the tofu and green onions."))
    ) + listOf(
        "Shakshuka" to listOf("1 onion, sliced", "2 red peppers, sliced", "1 can (28 oz) crushed tomatoes", "6 eggs"),
        "Greek Salad" to listOf("4 tomatoes", "1 cucumber", "200 g feta", "1/2 cup kalamata olives"),
        "Pad Thai" to listOf("200 g rice noodles", "2 eggs", "3 tbsp fish sauce", "1 cup bean sprouts"),
        "Mushroom Risotto" to listOf("1 1/2 cups arborio rice", "500 g mushrooms", "6 cups chicken stock", "1/2 cup parmesan"),
        "Fish Tacos" to listOf("1 lb white fish", "8 corn tortillas", "2 cups shredded cabbage", "1 lime"),
        "Chocolate Chip Cookies" to listOf("2 1/4 cups flour", "1 cup butter", "3/4 cup sugar", "2 cups chocolate chips"),
        "Buttermilk Pancakes" to listOf("2 cups flour", "2 cups buttermilk", "2 eggs", "2 tbsp sugar"),
        "Tomato Basil Soup" to listOf("2 lb tomatoes", "1 onion, chopped", "4 cups vegetable broth", "1 cup basil leaves"),
        "Beef Stir-Fry" to listOf("1 lb flank steak", "2 cups broccoli florets", "3 tbsp soy sauce", "1 tbsp cornstarch"),
        "Lemon Garlic Salmon" to listOf("4 salmon fillets", "3 cloves garlic, minced", "1 lemon", "2 tbsp butter"),
        "Guacamole" to listOf("3 avocados", "1 lime", "1/2 red onion, diced", "1 jalapeño"),
        "French Toast" to listOf("8 slices bread", "4 eggs", "1 cup milk", "1 tsp cinnamon"),
        "Caprese Salad" to listOf("3 tomatoes", "250 g fresh mozzarella", "1 cup basil leaves", "2 tbsp olive oil")
    ).map { (title, ingredients) ->
        Seed(title, title.lowercase().replace(" ", "-"), "4", ingredients, listOf("Prepare the ingredients.", "Cook and serve."))
    }

    /**
     * The typed-decision model's answers, simulated (#99, #104; iOS `UITestDecisionModel`): close
     * grocery or pantry names are the same thing; trailing text is junk if it holds "dfsafs",
     * else a note; a line with no separator ending in "dfsafs" is named by its words between the
     * amount and the junk ("2 eggs dfsafs" is "eggs"); anything else it can't answer.
     */
    fun decisionModel() = FakeDecisionModel(languages = setOf("en"), reply = { prompt ->
        when (prompt.kind) {
            DecisionKind.SAME_GROCERY, DecisionKind.SAME_INGREDIENT -> DecisionReply("same", "high")
            DecisionKind.TRAILING_TEXT -> DecisionReply(if ("dfsafs" in prompt.text) "junk" else "note", "high")
            DecisionKind.INGREDIENT_NAME -> prompt.text.substringAfter("): ").takeIf { it.endsWith(" dfsafs") }
                ?.removeSuffix(" dfsafs")?.split(' ')?.dropWhile { word -> word.any { it.isDigit() } }
                ?.joinToString(" ")?.let { DecisionReply(it, "high") }
            else -> null
        }
    })

    fun listFor(title: String): String = when (title) {
        "Banana Bread", "Buttermilk Pancakes", "French Toast" -> "Breakfast"
        "Sponge Cake", "Chocolate Chip Cookies" -> "Desserts"
        "Greek Salad", "Caprese Salad", "Tomato Basil Soup", "Miso Soup", "Grandma's Lentil Soup" -> "Lunch"
        "Guacamole" -> "Snacks"
        else -> "Dinner"
    }
}
