import Foundation

/// One reply from the model: its pick and how sure it says it is ("high", "medium", "low").
struct DecisionReply: Equatable {
    let answer: String
    let confidence: String
}

/// The confidence rule (#104), Android's `DecisionRule`: asked `asks` times with the options in
/// a different order each time, every reply must pick the same definite option with confidence
/// "high". Anything else is `unsure`.
enum DecisionRule {
    static let asks = 2

    static func judge(_ kind: DecisionKind, _ replies: [DecisionReply]) -> String {
        guard replies.count == asks else { return DecisionKind.unsure }
        let answers = replies.map { $0.answer.kTrimmed.lowercased() }
        let first = answers[0]
        let agreed = kind.options.contains(first) && answers.allSatisfy { $0 == first }
        let sure = replies.allSatisfy { $0.confidence.kTrimmed.lowercased() == "high" }
        return agreed && sure ? first : DecisionKind.unsure
    }

    /// The choices as ask `n` (0-based) lists them: as declared, then reversed; "unsure" last.
    static func options(_ kind: DecisionKind, _ n: Int) -> [String] {
        (n % 2 == 0 ? kind.options : kind.options.reversed()) + [DecisionKind.unsure]
    }
}

/// What the model is asked: `instructions`, then `text`, answering with one of `options`.
struct DecisionPrompt: Equatable {
    let kind: DecisionKind
    let instructions: String
    let text: String
    let options: [String]
}

/// The words of each question: the same text as Android's `DecisionPrompts`.
enum DecisionPrompts {
    private static let languages = [
        "en": "English", "de": "German", "es": "Spanish", "fr": "French",
        "it": "Italian", "pt": "Portuguese", "ja": "Japanese",
    ]

    static func prompt(_ question: DecisionQuestion, _ n: Int) -> DecisionPrompt {
        let options = DecisionRule.options(question.kind, n)
        let language = languages[question.language] ?? question.language
        let instructions: String, text: String
        switch question.kind {
        case .countBracket:
            (instructions, text) = (count, "Ingredient line (\(language)): \(question.input)")
        case .sameIngredient:
            let names = question.input.components(separatedBy: DecisionQuestion.pair)
            (instructions, text) = (same, "Ingredient names (\(language)): \"\(names[0])\" and \"\(names[names.count - 1])\"")
        case .aisle:
            (instructions, text) = (aisle, "Ingredient (\(language)): \(question.input)")
        case .sameGrocery:
            let names = question.input.components(separatedBy: DecisionQuestion.pair)
            (instructions, text) = (sameGrocery, "Shopping list items (\(language)): \"\(names[0])\" and \"\(names[names.count - 1])\"")
        case .trailingText:
            (instructions, text) = (trailing, "Text after the ingredient (\(language)): \(question.input)")
        }
        let closing = "\nAnswer with one of: \(options.joined(separator: ", ")). Give your confidence: high, medium or low. "
            + "Answer \"unsure\" whenever you are not certain."
        return DecisionPrompt(kind: question.kind, instructions: instructions + closing, text: text, options: options)
    }

    private static let count = """
        A recipe's ingredient line starts with a count of items and has an amount in brackets after the name.
        Is the bracketed amount the total for all the items together ("total"), or the size of each single
        item ("each")? For example "4 apples (about 800 g)" is total: four apples weigh 800 g together.
        "3 large apples, peeled and sliced (about 3 cups)" is total. "2 chicken breasts (200 g each)" is each.
        """

    private static let same = """
        One name is an ingredient in a recipe, the other an item in the cook's pantry. Are they the same
        ingredient, so the pantry item is what the recipe asks for ("same"), or different ("different")?
        A name with an extra word that makes a different product is different: "rice flour" is not "flour",
        "whole milk" is not "milk", "brown sugar" is not "sugar". Only names for the same product are the same:
        "plain flour" and "all-purpose flour", "double cream" and "heavy cream".
        """

    private static let sameGrocery = """
        Two items on a shopping list, each the ingredient named in a recipe line. Would a shopper buy the
        same product for both ("same"), or are they different products ("different")? Only a different
        wording of one product is the same: "ears of corn" and "corn", "corn on the cob" and "corn",
        "garlic cloves" and "garlic". A word that makes another product is different: "rice flour" is not
        "flour", "whole milk" is not "milk", "brown sugar" is not "sugar", "corn flour" is not "corn".
        """

    private static let trailing = """
        A shopping list line from a recipe has some text after the ingredient's name. What is that text?
        "note": only a description or preparation, no amount and no other ingredient (", shucked",
        ", finely chopped", "(optional)", ", at room temperature"). "second_amount": it gives another
        amount or size, or another ingredient or an alternative ("(about three cups)", "plus two yolks",
        ", or frozen corn", "and some for the pan"). "junk": meaningless characters or a typing error
        ("(dfsafs -", "--- xx").
        """

    private static let aisle = """
        Which supermarket aisle is this ingredient found in? Use "other" when none of the aisles fits.
        """
}
