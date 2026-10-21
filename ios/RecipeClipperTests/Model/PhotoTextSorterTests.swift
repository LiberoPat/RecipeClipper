import XCTest
@testable import RecipeClipper

/// Android's `PhotoTextSorterTest` (#198): the same cases and expectations.
final class PhotoTextSorterTests: XCTestCase {
    /// A recipe card as the recogniser reads it: a title, headers, a numbered method.
    private let card = [
        "Aunt June's Oatmeal Cookies",
        "Ingredients",
        "1 cup butter",
        "1 1/2 cups flour",
        "2 cups rolled oats",
        "Directions",
        "1. Cream the butter.",
        "2. Stir in the flour and oats.",
        "3. Bake at 350°F for 12 minutes.",
    ]

    private func lines(_ texts: [String], unsure: Set<String> = []) -> [PhotoLine] {
        texts.map { PhotoLine(text: $0, confidence: unsure.contains($0) ? Float(0.3) : Float(1)) }
    }

    /// Whether `PhotoTextSorter.check` lists `line` as an ingredient line, however sure the recogniser was.
    private func flagged(_ line: String, words: LanguageWords = .english) -> Bool {
        !PhotoTextSorter.check(line, words: words).isEmpty
    }

    func testACardsLinesSortIntoIngredientsAndStepsLikeATypedPost() {
        let reading = PhotoTextSorter.sort(lines(card))

        XCTAssertTrue(reading.sorted)
        XCTAssertEqual(reading.ingredients, ["1 cup butter", "1 1/2 cups flour", "2 cups rolled oats"])
        XCTAssertEqual(reading.instructions, ["Cream the butter.", "Stir in the flour and oats.", "Bake at 350°F for 12 minutes."])
        XCTAssertEqual(reading.uncertain, [])
    }

    func testLinesTheRecogniserWasUnsureOfAreMarkedAsTheyAreShown() {
        let reading = PhotoTextSorter.sort(lines(card, unsure: ["1 1/2 cups flour", "3. Bake at 350°F for 12 minutes."]))

        // The step lost its "3." to the splitter and is still marked.
        XCTAssertEqual(reading.uncertain, ["1 1/2 cups flour", "Bake at 350°F for 12 minutes."])
    }

    func testAMisreadAmountStaysAsReadNeverCorrected() {
        let misread = card.map { $0 == "1 cup butter" ? "l cup butter" : $0 }
        let reading = PhotoTextSorter.sort(lines(misread, unsure: ["l cup butter"]))

        XCTAssertEqual(reading.ingredients.first, "l cup butter")
        XCTAssertEqual(reading.uncertain, ["l cup butter"])
    }

    func testAMixedNumberThatLostItsSpaceIsCheckedHoweverSureTheRecogniserWas() {
        // Read from a real card by Vision, at full confidence: "1 1/2 cups flour".
        let misread = card.map { $0 == "1 1/2 cups flour" ? "11/2 cups flour" : $0 }
        let reading = PhotoTextSorter.sort(lines(misread))

        XCTAssertEqual(reading.ingredients[1], "11/2 cups flour")
        XCTAssertEqual(reading.uncertain, ["11/2 cups flour"])
    }

    func testCheckedLinesKeepTheOrderTheyAreShownIn() {
        let misread = card.map { line -> String in
            switch line {
            case "1 cup butter": return "1 cupbutter"
            case "3. Bake at 350°F for 12 minutes.": return "3. Bake at 35O°F for 12 minutes."
            default: return line
            }
        }
        let reading = PhotoTextSorter.sort(lines(misread, unsure: ["2 cups rolled oats"]))

        XCTAssertEqual(reading.uncertain, ["1 cupbutter", "2 cups rolled oats", "Bake at 35O°F for 12 minutes."])
    }

    func testAmountsShapedLikeAMisreadingAreFlagged() {
        for line in [
            "11/2 cups flour", "13/4 cups sugar", "21/2 tsp baking soda", "31/3 cups milk",
            "Bake for 11/2 hours.", "3/2 cup water",
            "l/2 cup sugar", "I/4 tsp salt", "O.5 kg potatoes", "1/Z cup milk", "1/S tsp salt",
            "1O eggs", "Bake at 35o°F.", "l2 eggs", "|/2 cup oil",
            "1 cupraisitos", "1 cupraisins", "2 tbspsugar", "1 teaspoonvanilla",
        ] {
            XCTAssertTrue(flagged(line), line)
        }
    }

    func testOrdinaryAmountsAreNotFlagged() {
        for line in [
            "1 1/2 cups flour", "1/2 cup sugar", "3/4 tsp salt", "2/2 cups", "1-1/2 cups oats",
            "1/2-3/4 cup milk", "11/16 inch", "1 cup raisins", "1cup flour", "2 green onions",
            "3 carrots", "12 cupcake liners", "1oz chocolate", "1l milk", "10 oz spinach",
            "2 lb chicken", "350°F", "Bake at 350°F for 12 minutes.", "Cool for 10 min.Serve.",
            "Aunt June's Oatmeal Cookies", "Stir in 2 eggs", "0.5 kg potatoes", "1,5 kg flour",
        ] {
            XCTAssertFalse(flagged(line), line)
        }
    }

    func testNoConfidenceIsNotLowConfidence() {
        let reading = PhotoTextSorter.sort(card.map { PhotoLine(text: $0, confidence: nil) })

        XCTAssertEqual(reading.uncertain, [])
    }

    func testAShortUnsurePieceMarksOnlyALineThatIsExactlyIt() {
        let reading = PhotoTextSorter.sort(lines(card + ["1"], unsure: ["1"]))

        XCTAssertFalse(reading.uncertain.contains("1 cup butter"))
    }

    func testLinesThatDontSortComeBackUnsortedEveryOneAsRead() {
        let prose = ["Mix the butter and sugar,", "then the flour. Bake till golden."]
        let reading = PhotoTextSorter.sort(lines(prose, unsure: ["then the flour. Bake till golden."]))

        XCTAssertFalse(reading.sorted)
        XCTAssertEqual(reading.ingredients, prose)
        XCTAssertEqual(reading.instructions, [])
        XCTAssertEqual(reading.uncertain, ["then the flour. Bake till golden."])
    }

    func testAPhotoWithNoTextReadsAsEmpty() {
        let reading = PhotoTextSorter.sort([PhotoLine(text: "  "), PhotoLine(text: "")])

        XCTAssertFalse(reading.sorted)
        XCTAssertTrue(reading.isEmpty)
    }

    func testAGallerysPicturesReadAsOneTextInOrder() {
        // The front has the ingredients, the back the method.
        let reading = PhotoTextSorter.sort(lines(Array(card.prefix(5))) + lines(Array(card.dropFirst(5))))

        XCTAssertTrue(reading.sorted)
        XCTAssertEqual(reading.ingredients.count, 3)
        XCTAssertEqual(reading.instructions.count, 3)
    }

    // Fraction glyphs (#245): what ML Kit and Vision really gave for ½ ¼ ¾ ⅓ ⅔ ⅛ on the
    // card-fractions fixtures, and the percentages that must stay unflagged.

    func testAFractionGlyphReadAsALookAlikeInAnAmountsPlaceIsFlagged() {
        for line in ["2% cups oats", "2 % cups oats", "1 % cups flour", "% cup sugar", "& tsp nutmeg",
                     "/2 cup sugar", "/4 tsp salt", "1 Y4 cups flour", "Ys cup mik", "Vs tsp nutmeg",
                     "is tsp nutmeg", "1 a cups flour", "%"] {
            XCTAssertTrue(flagged(line), line)
        }
    }

    func testARealPercentageOrALookAlikeOutOfAnAmountsPlaceIsNotFlagged() {
        for line in ["2% milk", "1 cup 2% milk", "70% chocolate", "2 oz 70% chocolate", "200 g 70 % chocolate",
                     "a cup of milk", "1 apple", "salt & pepper", "Reduce to 50% power.", "2 % Milch",
                     "1 isn't", "½ cup sugar", "1¼ cups flour", "2 ⅔ cups oats"] {
            XCTAssertFalse(flagged(line), line)
        }
    }

    func testAFractionGlyphRunIntoADigitOrAnotherGlyphIsFlagged() {
        XCTAssertTrue(flagged("3¾4 tsp salt"))
        XCTAssertTrue(flagged("½⅓ cup milk"))
    }

    func testAChipReplacesAGluedGlyphRunWholeKeepingTheWholeNumber() {
        XCTAssertEqual(PhotoTextSorter.replaceFraction("3¾4 tsp salt", "¾"), "3 ¾ tsp salt") // glued: kept, set apart
        XCTAssertEqual(PhotoTextSorter.replaceFraction("½⅓ cup milk", "⅓"), "⅓ cup milk")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("2 ½⅔ cups oats", "⅔"), "2 ⅔ cups oats")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("Add ½⅓ cup milk.", "½"), "Add ½ cup milk.")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("1½⅓ cup milk", "⅓"), "1 ⅓ cup milk")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("12½⅓", "½"), "12 ½")
        // No run: an ordinary glyph, a mixed number, a glyph after digits.
        for line in ["½ cup sugar", "1¼ cups flour", "2 ⅔ cups oats", "12½ g", "1 ½ cups"] {
            XCTAssertNil(PhotoTextSorter.fractionSlot(line), line)
        }
    }

    func testTheGluedRunIsEveryGlyphAndDigitFromItsFirstGlyphOn() {
        XCTAssertEqual(FractionLookAlike.gluedRun("3¾4 tsp"), NSRange(location: 1, length: 2))
        XCTAssertEqual(FractionLookAlike.gluedRun("½⅓ cup"), NSRange(location: 0, length: 2))
        XCTAssertEqual(FractionLookAlike.gluedRun("2 ½⅓ cups"), NSRange(location: 2, length: 2))
        XCTAssertEqual(FractionLookAlike.gluedRun("1½⅓4"), NSRange(location: 1, length: 3))
        XCTAssertNil(FractionLookAlike.gluedRun("1¼ cups"))
        XCTAssertNil(FractionLookAlike.gluedRun("2 ⅔ cups"))
    }

    // Unit misspellings (#276): a near-miss of a unit where the unit goes, from splitter.json's
    // short unitLookAlikes, never edit distance.

    func testANearMissOfAUnitAfterTheAmountIsFlagged() {
        for line in ["½ oup sugar", "1 oups flour", "2 cuo milk", "1 c up milk", "1 C UPS milk", "2 tsb salt",
                     "1 tps vanilla", "2 tbps butter", "1 tbsb oil", "1 isp salt", "1 ½ oup sugar", "2 tbps. butter"] {
            XCTAssertTrue(flagged(line), line)
        }
        XCTAssertTrue(flagged("2 e l Zucker", words: LanguageWords.forTag("de")!))
        XCTAssertTrue(flagged("1 oucharada de azúcar", words: LanguageWords.forTag("es")!))
        XCTAssertTrue(flagged("2 ouillères de sucre", words: LanguageWords.forTag("fr")!))
        XCTAssertTrue(flagged("1 oucchiaino di sale", words: LanguageWords.forTag("it")!))
        XCTAssertTrue(flagged("2 oolheres de açúcar", words: LanguageWords.forTag("pt")!))
    }

    func testRealUnitsWordsAndIngredientNamesAreNotFlaggedAsMisspeltUnits() {
        for line in ["1 cup milk", "2 cups flour", "1 c milk", "1 c. sugar", "2 tsp salt", "1 tbsp oil", "2 tbs butter",
                     "2 T sugar", "1 t salt", "1 oz butter", "2 g salt", "100 ml milk", "1 l water", "1 lb beef",
                     "2 lbs beef", "1 kg flour", "3 teaspoons sugar", "2 tablespoons oil", "1 stick butter",
                     "1 cupcake", "2 c upside-down cakes", "Bake for 1 hour.",
                     "4 tsp salt", "½ cup mik", "2 tsps sugar", "1 oupa", "Add 1 oup sugar"] {
            XCTAssertFalse(flagged(line), line)
        }
        for (lang, line) in [("de", "2 EL Zucker"), ("de", "1 TL Salz"), ("de", "1 Ei"), ("es", "1 cucharada de azúcar"),
                             ("fr", "2 cuillères à soupe de sucre"), ("it", "1 cucchiaino di sale"),
                             ("pt", "2 colheres de açúcar"), ("es", "1 taza de harina"), ("ja", "大さじ1 砂糖")] {
            XCTAssertFalse(flagged(line, words: LanguageWords.forTag(lang)!), line)
        }
    }

    func testAChipReplacesTheLookAlikeInPlaceAndNothingElse() {
        XCTAssertEqual(PhotoTextSorter.replaceFraction("2% cups oats", "⅔"), "2⅔ cups oats")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("2 % cups oats", "⅔"), "2 ⅔ cups oats")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("& tsp nutmeg", "⅛"), "⅛ tsp nutmeg")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("/4 tsp salt", "¾"), "¾ tsp salt")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("1 a cups flour", "¼"), "1 ¼ cups flour")
        XCTAssertEqual(PhotoTextSorter.replaceFraction("Ys cup mik", "⅓"), "⅓ cup mik")
        XCTAssertNil(PhotoTextSorter.replaceFraction("2% milk", "½"))
        XCTAssertNil(PhotoTextSorter.replaceFraction("½ cup sugar", "½"))
        XCTAssertEqual(PhotoTextSorter.fractions, ["½", "¼", "¾", "⅓", "⅔", "⅛"])
    }

    func testTheFractionCardsLinesAreListedToCheck() {
        // ML Kit's reading of card-fractions-hand.jpg on the emulator (#245).
        let reading = PhotoTextSorter.sort(lines([
            "Oat Cookies", "INGREDIENTS", "½ oup sugar", "1 a cups flour", "4 tsp salt", "Ys cup mik",
            "2 % cups oats", "& tsp nutmeg", "2 oz 70% chocolate", "METHOD", "Mix the dry ingredients.",
        ]))
        // "½ oup sugar"'s unit is misspelt (#276); "4 tsp salt" (¾ lost) and "mik" stay #249's.
        XCTAssertEqual(reading.uncertain, ["½ oup sugar", "1 a cups flour", "Ys cup mik", "2 % cups oats", "& tsp nutmeg"])
    }

    func testVisionsGluedReadingOfTheFractionCardGetsChips() {
        // Vision's reading of card-fractions-hand.jpg on the iOS simulator (#245, #276).
        let reading = PhotoTextSorter.sort(lines([
            "Oat Cookies", "INGREDIENTS", "½ cup sugar", "1¼ cups flour", "3/4 tsp salt", "½⅓ cup milk",
            "23 cups oats", "is tsp nutmeg", "2 oz 70% chocolate", "METHOD", "Mix the dry ingredients.",
        ]))
        // "23 cups oats" (2 ⅔) is too many cups (#249): flagged, but with no chips.
        XCTAssertEqual(reading.uncertain, ["½⅓ cup milk", "23 cups oats", "is tsp nutmeg"])
        XCTAssertEqual(reading.uncertain.filter { PhotoTextSorter.fractionSlot($0) != nil }, ["½⅓ cup milk", "is tsp nutmeg"])
        // Only its size is wrong, so it gets its own reason (#281).
        XCTAssertEqual(reading.large, ["23 cups oats"])
    }

    func testAFractionReadAsDigitsIsFlaggedWhenTheAmountIsImplausibleForItsUnit() {
        // Vision's readings of ¾, 2 ⅔ and ⅛ (#245, #249), and ML Kit's "2 23" for 2 ⅔.
        for line in [
            "34 tsp salt", "23 cups oats", "18 tsp nutmeg", "2 23 cups oats", "14 tsp. cinnamon",
            "12 tsp sugar", "34 Tbsp butter", "13 c milk", "10–14 tsp salt", "12 kg flour", "30 lb potatoes",
            "6000 g Mehl", "13 sticks butter", "14 cups milk",
        ] {
            XCTAssertTrue(PhotoTextSorter.implausible(line), line)
            XCTAssertTrue(flagged(line), line)
        }
        XCTAssertTrue(PhotoTextSorter.implausible("34 EL Zucker", words: LanguageWords.forTag("de")!))
        XCTAssertTrue(PhotoTextSorter.implausible("18 cucharaditas de sal", words: LanguageWords.forTag("es")!))
    }

    func testAPlausibleAmountACountOrATemperatureIsNotFlagged() {
        for line in [
            // At a unit's maximum, a big batch, decimals, ranges, and misreads too plausible to catch.
            "10 tsp baking powder", "11 cups chicken stock", "16 tablespoons (227g) unsalted butter",
            "2–3 tbsp olive oil", "2 to 3 tbsp olive oil", "1.5 cups flour", "0.75 tsp salt", "1 1/2 cups sugar",
            "4 tsp salt", "8 tsp nutmeg", "32 oz chicken stock", "1000 g flour", "2 14 oz cans tomatoes",
            // Counts and no units are never checked; a bare Celsius temperature is no amount of cups.
            "12 eggs", "48 wonton wrappers", "180 C water", "350°F", "Bake for 25 minutes.",
        ] {
            XCTAssertFalse(PhotoTextSorter.implausible(line), line)
        }
        XCTAssertFalse(PhotoTextSorter.implausible("1,5 kg Mehl", words: LanguageWords.forTag("de")!))
        XCTAssertFalse(PhotoTextSorter.implausible("1.500 g Mehl", words: LanguageWords.forTag("de")!))
    }

    func testAnImplausibleAmountIsFlaggedNeverChangedAndTheScalerStillReadsIt() {
        let reading = PhotoTextSorter.sort(lines(["INGREDIENTS", "34 tsp salt", "1 cup flour", "METHOD", "1. Mix."]))
        XCTAssertEqual(reading.ingredients, ["34 tsp salt", "1 cup flour"])
        XCTAssertEqual(reading.uncertain, ["34 tsp salt"])
        XCTAssertEqual(IngredientScaler.scale("34 tsp salt", factor: 2.0), "68 tsp salt")
    }

    func testAnAmountShapedLikeAFractionsDigitsIsHeldToItsUnitsLowerBar() {
        // ½, ¼ and ⅓ read as digits (#281): under the unit's maximum, over its fraction-shaped bar.
        for line in [
            "12 tbsp vinegar", "13 tbsp. honey", "12 cup sugar", "12 cups chicken stock", "12 lb ground beef",
            "1 12 cups flour", "2 14 tbsp butter",
        ] {
            XCTAssertTrue(PhotoTextSorter.implausible(line), line)
        }
        // Not shaped like a fraction, or a unit with no lower bar ("12 oz" and "15 g" are everyday).
        for line in [
            "16 tablespoons (227g) unsalted butter", "10 tbsp butter", "11 cups water", "10 cups flour",
            "12 oz spaghetti", "14 oz can coconut milk", "12 fl oz beer", "15 g yeast", "2 14 oz cans tomatoes",
        ] {
            XCTAssertFalse(PhotoTextSorter.implausible(line), line)
        }
    }

    func testEitherEndOfARangeIsHeldToItsUnitsOwnMaximumNeverTheFractionShapedBar() {
        // The owner's call (#283): "12–14 lb turkey" is a real range, not ½ and ¼ read as digits.
        for line in ["16–18 lb turkey", "12–14 lb turkey", "10–12 cups water", "12 to 14 tbsp butter", "2 to 3 tbsp olive oil"] {
            XCTAssertFalse(PhotoTextSorter.implausible(line), line)
        }
        for line in ["30–40 lb potatoes", "10–14 tsp salt", "12–14 cups water"] {
            XCTAssertTrue(PhotoTextSorter.implausible(line), line)
        }
    }

    func testAFractionsDigitsAreNeverATemperature() {
        // ⅝ and ⅞ read as digits fall in a plausible Celsius range (#283): "1 58 C sugar" is 1 ⅝ cups.
        for line in ["1 58 C sugar", "2 78 c. flour", "2 78 c flour", "58 C sugar", "78 c. milk"] {
            XCTAssertTrue(PhotoTextSorter.implausible(line), line)
        }
        for line in ["180 C water", "2 180 C water", "3 180 c water", "40 C water"] {
            XCTAssertFalse(PhotoTextSorter.implausible(line), line)
        }
    }

    func testASecondNumberIsJudgedOnlyAfterAPlainWholeOneByItsOwnTemperature() {
        // After a range, a fraction or a decimal, the lead's amounts aren't dropped for it (#281).
        for line in ["1–2 34 tsp salt", "½ 34 tsp salt", "1.5 34 tsp salt"] {
            XCTAssertFalse(PhotoTextSorter.implausible(line), line)
        }
        XCTAssertTrue(PhotoTextSorter.implausible("2 34 tsp salt"))
        // The temperature exemption tests the number judged: 180 is one, 14 isn't.
        XCTAssertFalse(PhotoTextSorter.implausible("2 180 C water"))
        XCTAssertTrue(PhotoTextSorter.implausible("180 14 c milk"))
    }

    func testAStepShapedLikeAnIngredientLineIsSizeCheckedAsAGlazeAfterTheMethodIs() {
        // #283: the splitter files a section after the method under the steps.
        let glaze = PhotoTextSorter.sort(lines([
            "Lemon Loaf", "INGREDIENTS", "1 1/2 cups flour", "1 cup sugar", "DIRECTIONS", "1. Mix and bake 50 minutes.",
            "GLAZE", "1 cup powdered sugar", "34 tsp vanilla",
        ]))
        XCTAssertEqual(glaze.instructions, ["Mix and bake 50 minutes.", "GLAZE", "1 cup powdered sugar", "34 tsp vanilla"])
        XCTAssertEqual(glaze.uncertain, ["34 tsp vanilla"])
        XCTAssertEqual(glaze.large, ["34 tsp vanilla"])
        for heading in ["Frosting:", "For the sauce:"] {
            let reading = PhotoTextSorter.sort(lines([
                "INGREDIENTS", "1 cup flour", "2 eggs", "METHOD", "1. Mix and bake.", heading, "34 tsp vanilla", "2 cups powdered sugar",
            ]))
            XCTAssertEqual(reading.large, ["34 tsp vanilla"], heading)
        }
    }

    func testAStepsAmountMidSentenceAConversionAndAStepNLineAreNeverSizeChecked() {
        let sorted = PhotoTextSorter.sort(lines([
            "INGREDIENTS", "1 cup flour", "48 tsp = 1 cup", "METHOD", "Stir in 34 tsp salt.", "48 tsp = 1 cup",
            "Step 3: 34 tsp salt",
        ]))
        XCTAssertTrue(sorted.sorted)
        XCTAssertEqual(sorted.ingredients, ["1 cup flour", "48 tsp = 1 cup"])
        XCTAssertEqual(sorted.instructions, ["Stir in 34 tsp salt.", "48 tsp = 1 cup", "34 tsp salt"])
        XCTAssertEqual(sorted.uncertain, [])
        XCTAssertEqual(PhotoTextSorter.check("34 tsp salt", labelled: true), [])
        XCTAssertEqual(PhotoTextSorter.check("34 tsp salt"), [.large])
        XCTAssertEqual(PhotoTextSorter.check("Stir in 34 tsp salt."), [])
    }

    func testUnsortedListMarkupGoesBeforeTheSizeCheckAndStepsAndConversionsAreSkipped() {
        let reading = PhotoTextSorter.sort(lines([
            "Grandma's card", "▢ 34 tsp salt", "• 12 tbsp vinegar", "- 23 cups oats", "3) 18 tsp nutmeg",
            "48 tsp = 1 cup", "Step 4: 34 tsp salt",
        ]))
        XCTAssertFalse(reading.sorted)
        let flagged = ["▢ 34 tsp salt", "• 12 tbsp vinegar", "- 23 cups oats", "3) 18 tsp nutmeg"]
        XCTAssertEqual(reading.uncertain, flagged)
        XCTAssertEqual(reading.large, flagged)
        // Listed without their markup, so the list doesn't show "• ▢ 34 tsp salt" (#283).
        XCTAssertEqual(reading.flags.map(\.shown), ["34 tsp salt", "12 tbsp vinegar", "23 cups oats", "18 tsp nutmeg"])
    }

    func testUnsortedEveryCheckRunsOnTheLineAsCleanedAndAChipKeepsTheLinesMarkup() {
        // #283: the look-alike, glued and misspelt-unit checks start where the amount does.
        let reading = PhotoTextSorter.sort(lines([
            "Grandma's card", "▢ 34 tsp salt", "▢ 2% cups oats", "• 1 oup sugar", "- 1 cupraisins",
        ]))
        XCTAssertFalse(reading.sorted)
        XCTAssertEqual(reading.flags, [
            LineFlag(raw: "▢ 34 tsp salt", shown: "34 tsp salt", reasons: [.large]),
            LineFlag(raw: "▢ 2% cups oats", shown: "2% cups oats", reasons: [.misread, .fraction]),
            LineFlag(raw: "• 1 oup sugar", shown: "1 oup sugar", reasons: [.misread]),
            LineFlag(raw: "- 1 cupraisins", shown: "1 cupraisins", reasons: [.misread]),
        ])
        XCTAssertEqual(
            PhotoTextSorter.replaceFraction(reading.flags[1], "⅔"),
            LineFlag(raw: "▢ 2⅔ cups oats", shown: "2⅔ cups oats", reasons: [.misread])
        )
        XCTAssertNil(PhotoTextSorter.replaceFraction(reading.flags[0], "½"))
    }

    func testALinesConfidenceIsItsOwnNeverThatOfALineQuotingIt() {
        // #283: the unsure step quotes the ingredient line, which was read clearly and is only too large.
        let reading = PhotoTextSorter.sort(lines(
            ["INGREDIENTS", "34 tsp salt", "1 cup flour", "DIRECTIONS", "Stir in 34 tsp salt."],
            unsure: ["Stir in 34 tsp salt."]
        ))
        XCTAssertEqual(reading.uncertain, ["34 tsp salt", "Stir in 34 tsp salt."])
        XCTAssertEqual(reading.large, ["34 tsp salt"])
        XCTAssertEqual(reading.flags[1].reasons, [.unsure])
    }

    func testALineWithAnotherReasonToCheckItIsntListedAsOnlyLarge() {
        // Both misread ("11/2") and too large ("34 tsp"): listed, but not as only large (#283).
        XCTAssertEqual(PhotoTextSorter.check("34 tsp salt, 11/2 tsp pepper"), [.misread, .large])
        let reading = PhotoTextSorter.sort(lines(
            ["INGREDIENTS", "34 tsp salt", "34 tsp salt, 11/2 tsp pepper", "12 tbsp vinegar", "METHOD", "Mix."],
            unsure: ["34 tsp salt"]
        ))
        XCTAssertEqual(reading.uncertain, ["34 tsp salt", "34 tsp salt, 11/2 tsp pepper", "12 tbsp vinegar"])
        XCTAssertEqual(reading.large, ["12 tbsp vinegar"])
        XCTAssertEqual(reading.flags[0].reasons, [.unsure, .large])
    }

    func testALineReadTwiceIsListedOnce() {
        // #283: Swift skipped only lines already flagged; a line is listed once, as on Android.
        let reading = PhotoTextSorter.sort(lines([
            "INGREDIENTS", "34 tsp salt", "1 cup flour", "34 tsp salt", "METHOD", "34 tsp salt", "Mix.",
        ]))
        XCTAssertEqual(reading.uncertain, ["34 tsp salt"])
    }

    func testAListedLineKeepsThePageAndBoxItWasReadAt() {
        let flour = PhotoBox(left: 0.1, top: 0.2, right: 0.8, bottom: 0.3)
        let sugar = PhotoBox(left: 0.1, top: 0.4, right: 0.6, bottom: 0.45)
        let reading = PhotoTextSorter.sort([
            PhotoLine(text: "INGREDIENTS", page: 0, box: PhotoBox(left: 0.1, top: 0.05, right: 0.5, bottom: 0.1)),
            // A caller's line with a break: each piece keeps the whole line's place.
            PhotoLine(text: "1 a cups flour\n2 % cups oats", page: 0, box: flour),
            PhotoLine(text: "½ oup sugar", confidence: 0.3, page: 0, box: sugar),
            PhotoLine(text: "1 egg", page: 0, box: PhotoBox(left: 0.1, top: 0.5, right: 0.4, bottom: 0.55)),
            PhotoLine(text: "METHOD", page: 1),
            PhotoLine(text: "1. Mix and bake.", page: 1),
            // Read again on the second page: listed once, where it was first read.
            PhotoLine(text: "½ oup sugar", page: 1, box: PhotoBox(left: 0.2, top: 0.5, right: 0.7, bottom: 0.55)),
        ])
        XCTAssertEqual(reading.flags.map(\.raw), ["1 a cups flour", "2 % cups oats", "½ oup sugar"])
        XCTAssertEqual(reading.flags.map(\.page), [0, 0, 0])
        XCTAssertEqual(reading.flags.map(\.box), [flour, flour, sugar])
        XCTAssertEqual(reading.flags[2].reasons, [.unsure, .misread])
    }

    func testALineReadWithNoPlaceIsListedWithNone() {
        let reading = PhotoTextSorter.sort([PhotoLine(text: "Grandma's card"), PhotoLine(text: "▢ 34 tsp salt")])
        XCTAssertEqual(reading.flags, [LineFlag(raw: "▢ 34 tsp salt", shown: "34 tsp salt", reasons: [.large])])
    }

    func testAFractionChipKeepsTheLinesPlace() {
        let box = PhotoBox(left: 0.1, top: 0.2, right: 0.8, bottom: 0.3)
        let flag = LineFlag(raw: "2 % cups oats", shown: "2 % cups oats", reasons: [.misread, .fraction], page: 1, box: box)
        XCTAssertEqual(
            PhotoTextSorter.replaceFraction(flag, "⅔"),
            LineFlag(raw: "2 ⅔ cups oats", shown: "2 ⅔ cups oats", reasons: [.misread], page: 1, box: box)
        )
    }

    func testAMalformedPlausibleAmountsTableIsRefusedAsSharedTablesDoes() {
        // A nil here is the app's fatalError, as SharedTables traps on a malformed table.
        XCTAssertNotNil(PlausibleAmounts(SharedTables.read("plausible-amounts")))
        XCTAssertNotNil(PlausibleAmounts(["maximums": ["TSP": 10], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["12"]]))
        let malformed: [SharedTables.Table] = [
            ["maximums": ["TSP": "ten"], "fractionShapedMaximums": [String: Any](), "fractionShapes": [String]()],
            ["maximums": ["TEASPOON": 10], "fractionShapedMaximums": [String: Any](), "fractionShapes": [String]()],
            ["fractionShapedMaximums": [String: Any](), "fractionShapes": [String]()],
            ["maximums": ["TSP": 10], "fractionShapes": [String]()],
            ["maximums": ["TSP": 10], "fractionShapedMaximums": ["TSP": 12], "fractionShapes": [String]()],
            ["maximums": ["TSP": 10], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["½"]],
            // Strict, as Android reads it (#283): a number written as a string, NaN, a boolean,
            // nothing above zero, and a fraction shape written as a number are all refused.
            ["maximums": ["TSP": "10"], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["12"]],
            ["maximums": ["TSP": "NaN"], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["12"]],
            ["maximums": ["TSP": true], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["12"]],
            ["maximums": ["TSP": Double.nan], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["12"]],
            ["maximums": ["TSP": 0], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["12"]],
            ["maximums": ["TSP": -1], "fractionShapedMaximums": [String: Any](), "fractionShapes": ["12"]],
            ["maximums": ["TBSP": 16], "fractionShapedMaximums": ["TBSP": "10"], "fractionShapes": ["12"]],
            ["maximums": ["TSP": 10], "fractionShapedMaximums": [String: Any](), "fractionShapes": [12]],
        ]
        for table in malformed {
            XCTAssertNil(PlausibleAmounts(table), "\(table)")
        }
        // The same as JSON, as the app reads it: JSONSerialization gives true as an NSNumber.
        for json in [
            #"{"maximums": {"TSP": true}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}"#,
            #"{"maximums": {"TSP": "10"}, "fractionShapedMaximums": {}, "fractionShapes": ["12"]}"#,
            #"{"maximums": {"TSP": 10}, "fractionShapedMaximums": {}, "fractionShapes": [12]}"#,
        ] {
            let table = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? SharedTables.Table
            XCTAssertNotNil(table, json)
            XCTAssertNil(table.flatMap(PlausibleAmounts.init), json)
        }
    }
}
