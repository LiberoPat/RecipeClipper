package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ahead-of-time step finder (#397): each phrase of each language's `ahead.json`, each skip
 * phrase, and the language picking the words. iOS's `AheadStepsTests` has the same cases, and
 * the differential corpus's `Ahead` rows pin the two together.
 */
class AheadStepsTest {

    private fun find(text: String, language: String = "en"): AheadStep? =
        AheadSteps.find(listOf(text), LanguageWords.forTag(language))

    private fun assertAhead(text: String, language: String = "en") =
        assertEquals("$language: $text", 0, find(text, language)?.step)

    private fun assertNone(text: String, language: String = "en") =
        assertNull("$language: $text", find(text, language))

    @Test fun anAlwaysPhraseIsEnough() {
        listOf(
            "Leave the dough in the fridge overnight.",
            "Prepare the base the night before.",
            "Do this the day before you serve it.",
            "Make the sauce a day ahead.",
            "This can be made ahead.",
            "A make-ahead dish.",
            "Thaw the chicken in the fridge."
        ).forEach(::assertAhead)
    }

    @Test fun aVerbNeedsTwoHours() {
        listOf(
            "Marinate at least 8 hours or overnight.",
            "Marinate the chicken for 4 hours.",
            "Brine the pork 12 hours.",
            "Soak the beans in water for 8 hours.",
            "Thaw for 3 hours.",
            "Defrost for 6 hours.",
            "Chill for at least 2 hours before serving.",
            "Refrigerate for 4-6 hours.",
            "Let the steak rest for two hours.",
            "Let the dough rise for 3 hours.",
            "Let the dough proof for several hours.",
            "Freeze for 4 hours until firm.",
            "For 8 hours, let the chicken marinate.",
            "Chill 8 to 12 hours.",
            "Chill for 2+ hours.",
            "Let it stand a couple of hours."
        ).forEach(::assertAhead)
    }

    @Test fun lessThanTwoHoursIsNotAhead() {
        listOf(
            "Marinate for 30 minutes.",
            "Let the dough rise for 1 to 2 hours.",
            "Chill for 1 hour.",
            "Chill for 1.5 hours.",
            "Rest for 90 minutes."
        ).forEach(::assertNone)
    }

    @Test fun aLimitIsNotAWait() {
        assertNone("Refrigerate for up to 2 hours.")
        assertNone("Chill no more than 3 hours.")
        assertNone("Marinate for a maximum of 4 hours.")
        // A wait with a limit after it still waits.
        assertAhead("Marinate at least 4 hours, up to 12 hours.")
    }

    @Test fun aSkipPhraseNeverTriggers() {
        listOf(
            "Serve with bread to soak up the sauce.",
            "Let the steak rest for 5 minutes.",
            "Add the overnight oats.",
            "There is no need to marinate overnight.",
            "It is not necessary to chill it for 4 hours.",
            "You don't need to soak the beans for 8 hours."
        ).forEach(::assertNone)
    }

    @Test fun aSkipPhraseOnlyBlocksWhatItOverlaps() {
        assertAhead("Soak up the sauce with bread. Chill for 4 hours.")
        assertAhead("Rest for 5 minutes, then marinate for 6 hours.")
    }

    @Test fun theHoursMustBeNearTheirVerb() {
        // Two hours, but nowhere near the verb "chill".
        assertNone("Chill the glasses and then wait for the guests to arrive and take a long walk around the garden for 2 hours.")
    }

    @Test fun plainWordsAndPlainStepsAreNotAhead() {
        listOf(
            "Preheat the oven to 350°F.",
            "Chop the onions and fry until golden.",
            "Marinate the chicken in the sauce.",
            "Let the dough rise until doubled.",
            "The price will rise. Bake for 2 hours."
        ).forEach(::assertNone)
    }

    @Test fun theFirstStepInOrderWins() {
        val steps = listOf("Mix the flour.", "Chill for 3 hours.", "Marinate overnight.")
        assertEquals(1, AheadSteps.find(steps, LanguageWords.ENGLISH)?.step)
        assertNull(AheadSteps.find(listOf("Mix.", "Bake."), LanguageWords.ENGLISH))
        assertNull(AheadSteps.find(emptyList(), LanguageWords.ENGLISH))
    }

    @Test fun theQuoteIsTheSentenceThatMatched() {
        val step = "Whisk the marinade together. Marinate at least 8 hours or overnight. Then grill."
        assertEquals(AheadStep(0, "Marinate at least 8 hours or overnight."), find(step))
        // A decimal point does not end a sentence.
        assertEquals("Chill for 2.5 hours.", find("Stir. Chill for 2.5 hours. Serve.")?.quote)
    }

    @Test fun aLongSentenceIsCutAtAWordWithAnEllipsis() {
        val long = "Marinate the chicken overnight in a large bowl with the garlic, the ginger, the soy sauce, " +
            "the honey, the lime juice and plenty of black pepper, turning it once."
        val quote = find(long)!!.quote
        assertEquals(
            "Marinate the chicken overnight in a large bowl with the garlic, the ginger, the soy sauce, the…",
            quote
        )
        assertEquals(true, quote.length <= AheadSteps.QUOTE_MAX + 1)
    }

    @Test fun lineBreaksInAQuoteAreOneSpace() {
        assertEquals("Marinate overnight, covered.", find("Marinate\novernight,\n  covered.")?.quote)
    }

    @Test fun theRecipesLanguagePicksTheWords() {
        assertAhead("Marinate overnight.", "en")
        // The same step as written in another language's recipe is read with that language's words.
        assertNone("Marinate overnight.", "de")
        assertNone("Marinate overnight.", "fr")
        assertAhead("Das Fleisch über Nacht marinieren.", "de")
        assertNone("Das Fleisch über Nacht marinieren.", "en")
        assertNone("Das Fleisch über Nacht marinieren.", "es")
    }

    @Test fun aLanguageWithNoTablesDetectsNothing() {
        assertNull(AheadSteps.find(listOf("Marinate overnight."), null))
        assertNull(AheadSteps.find(listOf("Marinate overnight."), LanguageWords.forTag("xx")))
    }

    @Test fun german() {
        listOf(
            "Das Fleisch über Nacht marinieren.", "Am Vortag den Teig kneten.", "Die Bohnen 12 Stunden einweichen.",
            "Den Teig 8 Std. im Kühlschrank ruhen lassen.", "Mindestens zwei Stunden kühlen.",
            "Das Hähnchen auftauen im Kühlschrank."
        ).forEach { assertAhead(it, "de") }
        listOf(
            "Mit Brot die Soße aufsaugen.", "5 Minuten ruhen lassen.", "Bis zu 2 Stunden kühlen.",
            "Nicht nötig, über Nacht zu marinieren."
        ).forEach { assertNone(it, "de") }
    }

    @Test fun spanish() {
        listOf(
            "Dejar marinar la carne durante la noche.", "Remojar los garbanzos 12 horas.", "Preparar la salsa el día anterior.",
            "Dejar enfriar en la nevera al menos 4 horas.", "Se puede preparar con antelación."
        ).forEach { assertAhead(it, "es") }
        listOf("Reposar 10 minutos.", "Refrigerar hasta 2 horas.", "No es necesario marinar toda la noche.")
            .forEach { assertNone(it, "es") }
    }

    @Test fun french() {
        listOf(
            "Laisser mariner le poulet toute la nuit.", "Mettre au frais 4 heures.", "Préparer la pâte la veille.",
            "Faire tremper les haricots 12 h.", "Préparer à l'avance."
        ).forEach { assertAhead(it, "fr") }
        listOf("Laisser reposer 10 minutes.", "Réfrigérer jusqu'à 2 heures.", "Inutile de faire mariner.")
            .forEach { assertNone(it, "fr") }
    }

    @Test fun italian() {
        listOf(
            "Lasciare marinare la carne per una notte.", "Lasciare lievitare per 2 ore.", "Preparare la sera prima.",
            "Mettere a bagno i ceci, ammollare 12 ore."
        ).forEach { assertAhead(it, "it") }
        listOf("Far riposare 5 minuti.", "Raffreddare fino a 2 ore.", "Non è necessario marinare tutta la notte.")
            .forEach { assertNone(it, "it") }
    }

    @Test fun portuguese() {
        listOf(
            "Deixe marinar de um dia para o outro.", "Deixe de molho por 8 horas.", "Prepare o molho na véspera.",
            "Deixe na geladeira por 4 horas.", "Descongele na geladeira."
        ).forEach { assertAhead(it, "pt") }
        listOf("Deixe descansar por 10 minutos.", "Deixe resfriar até 2 horas.", "Não é necessário marinar durante a noite.")
            .forEach { assertNone(it, "pt") }
    }

    @Test fun japanese() {
        listOf(
            "鶏肉を一晩漬け込む。", "冷蔵庫で3時間寝かせる。", "前日に作っておく。", "２時間漬ける。", "数時間浸す。"
        ).forEach { assertAhead(it, "ja") }
        listOf("10分寝かせる。", "最大2時間冷やす。", "漬ける必要はありません。").forEach { assertNone(it, "ja") }
        // A Japanese sentence ends at 。, with no space after it.
        assertEquals("冷蔵庫で3時間寝かせる。", find("鶏肉を切る。冷蔵庫で3時間寝かせる。焼く。", "ja")?.quote)
    }

    @Test fun everyLanguageHasTheTable() {
        for (language in LanguageWords.SHIPPED) {
            val words = LanguageWords.forTag(language)!!
            for (key in listOf("always", "verbs", "hours", "counts", "upTo", "skip")) {
                assert(words.strings("ahead", key).isNotEmpty()) { "$language ahead.json $key" }
            }
        }
    }
}
