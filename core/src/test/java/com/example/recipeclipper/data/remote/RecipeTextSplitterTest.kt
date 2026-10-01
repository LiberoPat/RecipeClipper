package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.remote.RecipeTextSplitter.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The iOS suite (`RecipeTextSplitterTests.swift`) has the same cases and expectations. */
class RecipeTextSplitterTest {

    private fun split(text: String) = RecipeTextSplitter.split(text)

    @Test fun `splits a Markdown recipe with bold headers, bullets and numbered steps`() {
        val result = split(
            """
            **Ingredients**

            * 2 cups flour
            * 1 tsp salt

            **Instructions**

            1. Mix everything.
            2. Bake for 20 minutes.
            """.trimIndent()
        )!!
        assertEquals(listOf("2 cups flour", "1 tsp salt"), result.ingredients)
        assertEquals(listOf("Mix everything.", "Bake for 20 minutes."), result.instructions)
        assertNull(result.yield)
    }

    @Test fun `prose without headers is never split`() {
        assertNull(split("I mixed 2 cups of flour with some butter and baked it at 350 for 20 minutes. So good!"))
        assertNull(split("2 cups flour\n1 tsp salt\nMix and bake for 20 minutes."))
    }

    @Test fun `needs both an ingredients and an instructions section, each with lines`() {
        assertNull(split("Ingredients:\n2 cups flour\n1 tsp salt"))
        assertNull(split("Directions:\nMix.\nBake."))
        assertNull(split("Ingredients:\n\nDirections:\nMix.\nBake."))
        assertNull(split("Ingredients:\n2 cups flour\nDirections:\n"))
    }

    @Test fun `a header must be the whole line`() {
        assertNull(split("Ingredients: flour, salt, water\nInstructions: mix and bake"))
        assertNull(split("The ingredients are simple\n2 cups flour\nThe method is easy\nBake."))
    }

    @Test fun `recognises the usual header spellings`() {
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("Ingredients"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("INGREDIENTS:"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("You'll need:"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("Ingredients (serves 4)"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("Ingredients for the cake:"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("Directions"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("Method -"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("Steps:"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("How to make it"))
        assertEquals(Section.END, RecipeTextSplitter.section("Notes:"))
        assertEquals(Section.END, RecipeTextSplitter.section("Edit: fixed formatting"))
        assertEquals(Section.END, RecipeTextSplitter.section("EDIT 2: thanks all"))
        assertNull(RecipeTextSplitter.section("Ingredients for this are cheap"))
        assertNull(RecipeTextSplitter.section("Preparation time: 10 min"))
        assertNull(RecipeTextSplitter.section("Update the seasoning to taste: salt"))
        assertNull(RecipeTextSplitter.section("2 cups flour"))
    }

    @Test fun `accepts the header styles people type on Reddit`() {
        val ingredientHeaders = listOf(
            "**Ingredients**", "**Ingredients:**", "**Ingredients**:", "Ingredients:", "## Ingredients", "INGREDIENTS",
            "__Ingredients__", "*Ingredients*", "Ingredients 🛒", "What you’ll need:", "**Things You'll Need**",
            "**Ingredients** (serves 4)", "**Ingredients [for 3~4 people]**"
        )
        val stepHeaders = listOf(
            "**Instructions**", "**Directions:**", "**Method**:", "Steps:", "## Preparation", "DIRECTIONS",
            "__Method__", "*Instructions*", "Directions 👇", "Procedure:", "**Cooking steps (for the sauce):**",
            "**Instructions/Method**", "How to make it:"
        )
        ingredientHeaders.zip(stepHeaders).forEach { (i, s) ->
            val result = split("A story first.\n\n$i\n\n* 2 cups flour\n* 1 tsp salt\n\n$s\n\n1. Mix.\n2. Bake.")
            assertEquals("$i / $s", listOf("2 cups flour", "1 tsp salt"), result?.ingredients)
            assertEquals("$i / $s", listOf("Mix.", "Bake."), result?.instructions)
        }
    }

    @Test fun `a header set apart as one may have a few words before its keyword`() {
        fun header(raw: String) = RecipeTextSplitter.header(RecipeTextSplitter.line(raw))
        assertEquals(Section.INGREDIENTS, header("**Ingredient amounts**:")?.section)
        assertNull(header("**Ingredient amounts**:")?.label)
        assertEquals(Section.INSTRUCTIONS, header("**Cooking instructions**")?.section)
        assertNull(header("**Cooking instructions**")?.label)
        // A word that names a group keeps it as the group's heading.
        assertEquals(Section.INGREDIENTS, header("**Dry ingredients**")?.section)
        assertEquals("Dry ingredients:", header("**Dry ingredients**")?.label)
        assertEquals("SAUCE INGREDIENTS:", header("SAUCE INGREDIENTS")?.label)
        assertEquals(Section.END, header("**Chef's notes**")?.section)
        // Not set apart, or a step: an ordinary line.
        assertNull(header("Dry ingredients"))
        assertNull(header("**Mix the dry ingredients**"))
        assertNull(header("**Stir in the wet ingredients**"))
        assertNull(header("1. **Instructions**"))
    }

    @Test fun `a misspelt header set apart as one is still a header`() {
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("Ingredeints:"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("**Ingrediants**"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("** Intructions**"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("DIRECTONS"))
        assertNull(RecipeTextSplitter.section("Ingredeints"))
        assertNull(RecipeTextSplitter.section("**Introductions**"))
    }

    @Test fun `steps with no header are a numbered list starting at 1`() {
        val result = split("**Ingredients**\n\n- 2 cups flour\n- 1 egg\n\n1. Mix.\n2. Bake at 350°F.\n\nEnjoy")!!
        assertEquals(listOf("2 cups flour", "1 egg"), result.ingredients)
        assertEquals(listOf("Mix.", "Bake at 350°F.", "Enjoy"), result.instructions)
        // Numbered ingredients, then steps numbered again from 1.
        val numbered = split("Ingredients:\n1. 2 cups flour\n2. 1 egg\n1. Mix.\n2. Bake.")!!
        assertEquals(listOf("2 cups flour", "1 egg"), numbered.ingredients)
        assertEquals(listOf("Mix.", "Bake."), numbered.instructions)
        // "Step 1" labels, bare or with a title.
        val labelled = split("INGREDIENTS\n2 cups flour\n\n**Step 1**\nMix the flour.\n\n**Step 2: Bake**\nBake 20 minutes.")!!
        assertEquals(listOf("Mix the flour.", "Bake:", "Bake 20 minutes."), labelled.instructions)
    }

    @Test fun `ingredients with no header are the amount lines just above the steps`() {
        val result = split(
            """
            I scaled one down for you. It makes about two servings.
            2.5 oz. frozen spinach
            Sour cream – 1/8 cup
            Salt to taste

            Directions:
            Stir together and chill.
            """.trimIndent()
        )!!
        assertEquals(listOf("2.5 oz. frozen spinach", "Sour cream – 1/8 cup", "Salt to taste"), result.ingredients)
        assertEquals(listOf("Stir together and chill."), result.instructions)
        // A label above the list isn't an ingredient; neither list needs a header.
        val bare = split("Recipe:\n\n* 1 cup dates\n* 1/4 cup honey\n* oats\n\n1. Blend the dates.\n2. Press into a pan.")!!
        assertEquals(listOf("1 cup dates", "1/4 cup honey", "oats"), bare.ingredients)
        assertEquals(listOf("Blend the dates.", "Press into a pan."), bare.instructions)
    }

    @Test fun `chatter never splits, even with a number or a list in it`() {
        assertNull(split("I used 2 cups of flour and it was fine.\nDirections:\nMix."))
        assertNull(split("My mum's way:\n1. lots of cheese\n2. a slow sauce\n3. patience"))
        assertNull(split("Honestly just wing it lol\n\nSteps:\n1. Buy it.\n2. Eat it."))
        assertNull(split("**Ingredients**\nwhatever is in the fridge\n\nThen bake it until it's done."))
    }

    @Test fun `a bold line inside a section names a group`() {
        val result = split("**Ingredients**\n**Cake**\n- 2 cups flour\n## Frosting\n- 1 cup sugar\n**Method**\n1. Bake.\n**Make the frosting**\n2. Whip.")!!
        assertEquals(listOf("Cake:", "2 cups flour", "Frosting:", "1 cup sugar"), result.ingredients)
        assertEquals(listOf("Bake.", "Make the frosting:", "Whip."), result.instructions)
        // A bold amount stays as written; headings alone aren't a recipe.
        assertEquals(listOf("2 cups flour"), split("Ingredients\n**2 cups flour**\nMethod\nBake.")!!.ingredients)
        assertNull(split("Ingredients\n**Cake**\nMethod\nBake."))
    }

    @Test fun `a line with a bare link is left out, a yield or time among the ingredients is read`() {
        val result = split("Ingredients\nServes 2\nPrep time: 5 min\n1 egg\nMethod\nBoil.\nMore on my blog: https://example.com/eggs")!!
        assertEquals(listOf("1 egg"), result.ingredients)
        assertEquals(listOf("Boil."), result.instructions)
        assertEquals("Serves 2", result.yield)
        assertEquals("5m", result.prepTime)
    }

    @Test fun `cleans Markdown off each line`() {
        val clean = RecipeTextSplitter::cleanLine
        assertEquals("2 cups flour", clean("* 2 cups flour"))
        assertEquals("2 cups flour", clean("- 2 cups flour"))
        assertEquals("2 cups flour", clean("• 2 cups flour"))
        assertEquals("Mix.", clean("1. Mix."))
        assertEquals("Mix.", clean("2) Mix."))
        assertEquals("Mix.", clean("Step 3: Mix."))
        assertEquals("1.5 cups milk", clean("1.5 cups milk"))
        assertEquals("Ingredients:", clean("**Ingredients:**"))
        assertEquals("Directions", clean("## Directions"))
        assertEquals("Directions", clean("> __Directions__"))
        assertEquals("#10 can tomatoes", clean("#10 can tomatoes"))
        assertEquals("King Arthur flour", clean("[King Arthur](https://example.com/flour) flour"))
        assertEquals("salt & pepper", clean("salt &amp; pepper"))
        assertEquals("1 cup sugar*", clean("1 cup sugar\\*"))
        assertEquals("butter, softened", clean("*butter, softened*"))
        assertEquals("", clean("---"))
        assertEquals("", clean("&#x200B;"))
        assertEquals("2 eggs", clean("  2 eggs  "))
        // Reddit's editor escapes what would otherwise be Markdown.
        assertEquals("Mix.", clean("1\\. Mix."))
        assertEquals("2 cups flour", clean("\\- 2 cups flour"))
        assertEquals("2 cups flour", clean("▢ 2 cups flour"))
        assertEquals("1 pound of pork belly", clean("・1 pound of pork belly"))
        assertEquals("Glaze (to be used during grilling)", clean("**Glaze** *(to be used during grilling)*"))
        assertEquals("Bake 20 minutes.", clean("Bake 20 minutes.\\"))
        assertEquals("-5°C freezer", clean("-5°C freezer"))
        assertEquals("", clean("==="))
    }

    @Test fun `the story before the first header is dropped, but a labelled yield and times are kept`() {
        val result = split(
            """
            This one is from my aunt, who made it every summer.
            Serves 4
            Prep time: 10 min
            Cook time: 1 hour 30 minutes
            Total time: Overnight

            Ingredients
            2 eggs
            Instructions
            Whisk.
            """.trimIndent()
        )!!
        assertEquals("Serves 4", result.yield)
        assertEquals("10m", result.prepTime)
        assertEquals("1h 30m", result.cookTime)
        assertEquals("Overnight", result.totalTime)
        assertEquals(listOf("2 eggs"), result.ingredients)
        assertEquals(listOf("Whisk."), result.instructions)
    }

    @Test fun `a labelled yield loses its label, serves and makes stay whole`() {
        assertEquals("12 cookies", split("Yield: 12 cookies\nIngredients\n1 egg\nMethod\nBake.")!!.yield)
        assertEquals("4", split("Servings: 4\nIngredients\n1 egg\nMethod\nBake.")!!.yield)
        assertEquals("Makes 2 loaves", split("Makes 2 loaves\nIngredients\n1 egg\nMethod\nBake.")!!.yield)
    }

    @Test fun `notes and edits after the steps are dropped`() {
        val result = split("Ingredients\n1 egg\nDirections\nBoil for 7 minutes.\nNotes\nUse fresh eggs.\nEdit: typo")!!
        assertEquals(listOf("Boil for 7 minutes."), result.instructions)
    }

    @Test fun `sections may repeat and come in either order`() {
        val result = split(
            """
            For the sauce
            Method
            Simmer the tomatoes.
            Ingredients
            1 can tomatoes
            Ingredients for the pasta:
            200 g spaghetti
            Directions
            Boil the pasta.
            """.trimIndent()
        )!!
        assertEquals(listOf("1 can tomatoes", "200 g spaghetti"), result.ingredients)
        assertEquals(listOf("Simmer the tomatoes.", "Boil the pasta."), result.instructions)
    }

    @Test fun `a sub-heading inside a section stays as written`() {
        val result = split("Ingredients\nFor the dough:\n2 cups flour\nInstructions\nKnead.")!!
        assertEquals(listOf("For the dough:", "2 cups flour"), result.ingredients)
    }

    @Test fun `windows and old-mac line endings split the same`() {
        val expected = split("Ingredients\n1 egg\nMethod\nBoil.")
        assertEquals(expected, split("Ingredients\r\n1 egg\r\nMethod\r\nBoil."))
        assertEquals(expected, split("Ingredients\r1 egg\rMethod\rBoil."))
    }
}
