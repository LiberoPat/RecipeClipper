import XCTest
@testable import RecipeClipper

/// The ahead-of-time step finder (#397): each phrase of each language's `ahead.json`, each skip
/// phrase, and the language picking the words. Android's `AheadStepsTest` has the same cases, and
/// the differential corpus's `Ahead` rows pin the two together.
final class AheadStepsTests: XCTestCase {

    private func find(_ text: String, _ language: String = "en") -> AheadStep? {
        AheadSteps.find([text], words: LanguageWords.forTag(language))
    }

    private func assertAhead(_ texts: [String], _ language: String = "en", file: StaticString = #filePath, line: UInt = #line) {
        for text in texts {
            XCTAssertEqual(find(text, language)?.step, 0, "\(language): \(text)", file: file, line: line)
        }
    }

    private func assertNone(_ texts: [String], _ language: String = "en", file: StaticString = #filePath, line: UInt = #line) {
        for text in texts {
            XCTAssertNil(find(text, language), "\(language): \(text)", file: file, line: line)
        }
    }

    func testAnAlwaysPhraseIsEnough() {
        assertAhead([
            "Leave the dough in the fridge overnight.",
            "Prepare the base the night before.",
            "Do this the day before you serve it.",
            "Make the sauce a day ahead.",
            "This can be made ahead.",
            "A make-ahead dish.",
            "Thaw the chicken in the fridge.",
        ])
    }

    func testAVerbNeedsTwoHours() {
        assertAhead([
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
            "Let it stand a couple of hours.",
        ])
    }

    func testLessThanTwoHoursIsNotAhead() {
        assertNone([
            "Marinate for 30 minutes.",
            "Let the dough rise for 1 to 2 hours.",
            "Chill for 1 hour.",
            "Chill for 1.5 hours.",
            "Rest for 90 minutes.",
        ])
    }

    func testALimitIsNotAWait() {
        assertNone(["Refrigerate for up to 2 hours.", "Chill no more than 3 hours.", "Marinate for a maximum of 4 hours."])
        // A wait with a limit after it still waits.
        assertAhead(["Marinate at least 4 hours, up to 12 hours."])
    }

    func testASkipPhraseNeverTriggers() {
        assertNone([
            "Serve with bread to soak up the sauce.",
            "Let the steak rest for 5 minutes.",
            "Add the overnight oats.",
            "There is no need to marinate overnight.",
            "It is not necessary to chill it for 4 hours.",
            "You don't need to soak the beans for 8 hours.",
        ])
    }

    func testASkipPhraseOnlyBlocksWhatItOverlaps() {
        assertAhead(["Soak up the sauce with bread. Chill for 4 hours."])
        assertAhead(["Rest for 5 minutes, then marinate for 6 hours."])
    }

    func testTheHoursMustBeNearTheirVerb() {
        assertNone(["Chill the glasses and then wait for the guests to arrive and take a long walk around the garden for 2 hours."])
    }

    func testPlainWordsAndPlainStepsAreNotAhead() {
        assertNone([
            "Preheat the oven to 350°F.",
            "Chop the onions and fry until golden.",
            "Marinate the chicken in the sauce.",
            "Let the dough rise until doubled.",
            "The price will rise. Bake for 2 hours.",
        ])
    }

    func testTheFirstStepInOrderWins() {
        let steps = ["Mix the flour.", "Chill for 3 hours.", "Marinate overnight."]
        XCTAssertEqual(AheadSteps.find(steps, words: .english)?.step, 1)
        XCTAssertNil(AheadSteps.find(["Mix.", "Bake."], words: .english))
        XCTAssertNil(AheadSteps.find([], words: .english))
    }

    func testTheQuoteIsTheSentenceThatMatched() {
        let step = "Whisk the marinade together. Marinate at least 8 hours or overnight. Then grill."
        XCTAssertEqual(find(step), AheadStep(step: 0, quote: "Marinate at least 8 hours or overnight."))
        // A decimal point does not end a sentence.
        XCTAssertEqual(find("Stir. Chill for 2.5 hours. Serve.")?.quote, "Chill for 2.5 hours.")
    }

    func testALongSentenceIsCutAtAWordWithAnEllipsis() throws {
        let long = "Marinate the chicken overnight in a large bowl with the garlic, the ginger, the soy sauce, "
            + "the honey, the lime juice and plenty of black pepper, turning it once."
        let quote = try XCTUnwrap(find(long)).quote
        XCTAssertEqual(quote, "Marinate the chicken overnight in a large bowl with the garlic, the ginger, the soy sauce, the…")
        XCTAssertLessThanOrEqual(quote.u16Count, AheadSteps.quoteMax + 1)
    }

    func testLineBreaksInAQuoteAreOneSpace() {
        XCTAssertEqual(find("Marinate\novernight,\n  covered.")?.quote, "Marinate overnight, covered.")
    }

    func testTheRecipesLanguagePicksTheWords() {
        assertAhead(["Marinate overnight."], "en")
        assertNone(["Marinate overnight."], "de")
        assertNone(["Marinate overnight."], "fr")
        assertAhead(["Das Fleisch über Nacht marinieren."], "de")
        assertNone(["Das Fleisch über Nacht marinieren."], "en")
        assertNone(["Das Fleisch über Nacht marinieren."], "es")
    }

    func testALanguageWithNoTablesDetectsNothing() {
        XCTAssertNil(AheadSteps.find(["Marinate overnight."], words: nil))
        XCTAssertNil(AheadSteps.find(["Marinate overnight."], words: LanguageWords.forTag("xx")))
    }

    func testGerman() {
        assertAhead([
            "Das Fleisch über Nacht marinieren.", "Am Vortag den Teig kneten.", "Die Bohnen 12 Stunden einweichen.",
            "Den Teig 8 Std. im Kühlschrank ruhen lassen.", "Mindestens zwei Stunden kühlen.",
            "Das Hähnchen auftauen im Kühlschrank.",
        ], "de")
        assertNone([
            "Mit Brot die Soße aufsaugen.", "5 Minuten ruhen lassen.", "Bis zu 2 Stunden kühlen.",
            "Nicht nötig, über Nacht zu marinieren.",
        ], "de")
    }

    func testSpanish() {
        assertAhead([
            "Dejar marinar la carne durante la noche.", "Remojar los garbanzos 12 horas.", "Preparar la salsa el día anterior.",
            "Dejar enfriar en la nevera al menos 4 horas.", "Se puede preparar con antelación.",
        ], "es")
        assertNone(["Reposar 10 minutos.", "Refrigerar hasta 2 horas.", "No es necesario marinar toda la noche."], "es")
    }

    func testFrench() {
        assertAhead([
            "Laisser mariner le poulet toute la nuit.", "Mettre au frais 4 heures.", "Préparer la pâte la veille.",
            "Faire tremper les haricots 12 h.", "Préparer à l'avance.",
        ], "fr")
        assertNone(["Laisser reposer 10 minutes.", "Réfrigérer jusqu'à 2 heures.", "Inutile de faire mariner."], "fr")
    }

    func testItalian() {
        assertAhead([
            "Lasciare marinare la carne per una notte.", "Lasciare lievitare per 2 ore.", "Preparare la sera prima.",
            "Mettere a bagno i ceci, ammollare 12 ore.",
        ], "it")
        assertNone(["Far riposare 5 minuti.", "Raffreddare fino a 2 ore.", "Non è necessario marinare tutta la notte."], "it")
    }

    func testPortuguese() {
        assertAhead([
            "Deixe marinar de um dia para o outro.", "Deixe de molho por 8 horas.", "Prepare o molho na véspera.",
            "Deixe na geladeira por 4 horas.", "Descongele na geladeira.",
        ], "pt")
        assertNone(["Deixe descansar por 10 minutos.", "Deixe resfriar até 2 horas.", "Não é necessário marinar durante a noite."], "pt")
    }

    func testJapanese() {
        assertAhead(["鶏肉を一晩漬け込む。", "冷蔵庫で3時間寝かせる。", "前日に作っておく。", "２時間漬ける。", "数時間浸す。"], "ja")
        assertNone(["10分寝かせる。", "最大2時間冷やす。", "漬ける必要はありません。"], "ja")
        // A Japanese sentence ends at 。, with no space after it.
        XCTAssertEqual(find("鶏肉を切る。冷蔵庫で3時間寝かせる。焼く。", "ja")?.quote, "冷蔵庫で3時間寝かせる。")
    }

    func testEveryLanguageHasTheTable() {
        for language in LanguageWords.shipped {
            let words = LanguageWords.forTag(language)!
            for key in ["always", "verbs", "hours", "counts", "upTo", "skip"] {
                XCTAssertFalse(words.strings("ahead", key).isEmpty, "\(language) ahead.json \(key)")
            }
        }
    }
}
