package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The confidence rule (#104): two asks, options in two orders, the same definite pick, both "high". */
class DecisionRuleTest {

    private fun reply(answer: String, confidence: String = "high") = DecisionReply(answer, confidence)

    @Test fun `the same definite answer twice with high confidence is accepted`() {
        assertEquals("total", DecisionRule.judge(DecisionKind.COUNT_BRACKET, listOf(reply("total"), reply(" Total "))))
        assertEquals("dairy", DecisionRule.judge(DecisionKind.AISLE, listOf(reply("dairy"), reply("dairy"))))
    }

    @Test fun `anything less is unsure`() {
        val kind = DecisionKind.SAME_INGREDIENT
        assertEquals("unsure", DecisionRule.judge(kind, listOf(reply("same"), reply("different"))))
        assertEquals("unsure", DecisionRule.judge(kind, listOf(reply("same"), reply("same", "medium"))))
        assertEquals("unsure", DecisionRule.judge(kind, listOf(reply("same", "low"), reply("same"))))
        assertEquals("unsure", DecisionRule.judge(kind, listOf(reply("unsure"), reply("unsure"))))
        assertEquals("unsure", DecisionRule.judge(kind, listOf(reply("yes"), reply("yes"))))
        assertEquals("unsure", DecisionRule.judge(kind, listOf(reply("same"))))
        assertEquals("unsure", DecisionRule.judge(DecisionKind.AISLE, listOf(reply("pharmacy"), reply("pharmacy"))))
    }

    @Test fun `the two asks list the options in different orders, unsure last`() {
        assertEquals(listOf("total", "each", "unsure"), DecisionRule.options(DecisionKind.COUNT_BRACKET, 0))
        assertEquals(listOf("each", "total", "unsure"), DecisionRule.options(DecisionKind.COUNT_BRACKET, 1))
        assertTrue("unsure" in DecisionRule.options(DecisionKind.AISLE, 0))
    }

    @Test fun `the pantry question names the owner's different pairs`() {
        val prompt = DecisionPrompts.prompt(DecisionQuestion.sameIngredient("Flour", "rice  flour", "en"), 0)
        assertTrue("\"rice flour\" is not \"flour\"" in prompt.instructions)
        assertTrue("\"whole milk\" is not \"milk\"" in prompt.instructions)
        assertTrue("\"flour\" and \"rice flour\"" in prompt.text)
        assertEquals(listOf("same", "different", "unsure"), prompt.options)
    }

    @Test fun `a question is the same whatever the case, spacing or order`() {
        assertEquals(
            DecisionQuestion.sameIngredient("Rice Flour", "flour", "en"),
            DecisionQuestion.sameIngredient(" flour", "rice   flour", "en")
        )
        assertEquals(DecisionQuestion.countBracket("4 Apfel  (ca. 800g)", "de"), DecisionQuestion.countBracket("4 apfel (ca. 800g)", "de"))
    }
}
