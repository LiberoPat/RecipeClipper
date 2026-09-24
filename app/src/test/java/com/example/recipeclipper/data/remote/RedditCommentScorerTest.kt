package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The iOS suite (`RedditCommentScorerTests.swift`) has the same cases and expectations. */
class RedditCommentScorerTest {

    private val score = RedditCommentScorer::score

    private val transcription = """
        Transcription:

        **Ingredients**
        - 1 cup dates
        - 1 cup flour

        **Directions**
        1. Mix.
        2. Bake at 350°F for 1 hour.
    """.trimIndent()

    private val recipeNoMention = "Ingredients\n1 cup dates\n1 cup flour\nDirections\nMix.\nBake."

    @Test fun `a full transcription scores every signal`() {
        // Ingredients 3 + Directions 3 + "Transcription" 2 + 7 lines 1
        assertEquals(9, score(transcription))
    }

    @Test fun `each signal on its own`() {
        assertEquals(7, score(recipeNoMention))
        assertEquals(4, score("Ingredients:\n1 cup dates\n1 cup water\nwalnuts"))
        assertEquals(4, score("Directions\nMix.\nBake.\nCool."))
        assertEquals(3, score("I transcribed it below.\nIt took a while.\nHer writing is tiny.\nEnjoy."))
        assertEquals(1, score("Such a lovely card.\nMy nan had one.\nSame tin too.\nThanks for sharing."))
    }

    @Test fun `short remarks score nothing, even when they mention transcribing`() {
        assertEquals(0, score("Looks delicious!"))
        assertEquals(0, score("Could someone transcribe this? I can't read it."))
        assertEquals(0, score(""))
        assertEquals(0, score("   \n\n  "))
    }

    @Test fun `deleted and removed comments score nothing`() {
        assertEquals(0, score("[deleted]"))
        assertEquals(0, score("[removed]"))
    }

    @Test fun `an implausibly long comment loses a point`() {
        val long = "Ingredients\n" + (1..80).joinToString("\n") { "$it g thing" } +
            "\nMethod\n" + (1..80).joinToString("\n") { "Stir $it times." }
        assertEquals(5, score(long))
    }

    @Test fun `pick takes the best comment that splits`() {
        val partial = "Ingredients:\n1 cup dates\n1 cup water\nwalnuts"
        assertEquals(
            transcription,
            RedditCommentScorer.pick(listOf("Lovely!", partial, recipeNoMention, transcription))
        )
    }

    @Test fun `ties go to the earlier comment`() {
        val other = "Ingredients\n2 cups dates\n2 cups flour\nDirections\nMix well.\nBake."
        assertEquals(recipeNoMention, RedditCommentScorer.pick(listOf(recipeNoMention, other)))
        assertEquals(other, RedditCommentScorer.pick(listOf(other, recipeNoMention)))
    }

    @Test fun `a high score without a clean split is passed over, never guessed at`() {
        // Mentions a transcription and lists ingredients, but has no steps section.
        val noSteps = "Transcription:\nIngredients\n1 cup dates\n1 cup flour\nMix and bake at 350."
        assertEquals(recipeNoMention, RedditCommentScorer.pick(listOf(noSteps, recipeNoMention)))
        assertNull(RedditCommentScorer.pick(listOf(noSteps)))
    }

    @Test fun `no comments, or none that are recipes, picks nothing`() {
        assertNull(RedditCommentScorer.pick(emptyList()))
        assertNull(RedditCommentScorer.pick(listOf("Recipe?", "[deleted]", "Could someone transcribe this?")))
    }
}
