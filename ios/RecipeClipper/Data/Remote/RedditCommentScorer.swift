import Foundation

/// Picks the comment on a Reddit post most likely to be the recipe. Ported from Android's
/// `RedditCommentScorer`, whose doc comment has the rules:
///  - an ingredients header line +3, an instructions header line +3 (as `RecipeTextSplitter`
///    recognises them); an explicit mention of transcribing +2;
///  - 4 to 150 non-empty lines +1, fewer than 4 -2, more than 150 -1; never below 0;
///    "[deleted]" and "[removed]" score 0.
/// `pick` ranks by score, ties to the earlier comment, and returns the first that actually
/// splits: a high score without clear structure is passed over, never guessed at.
///
/// Pure: text in, text out.
enum RedditCommentScorer {

    private static let transcription = JRegex(#"\btranscri(?:be|bed|bing|ption|ptions|pt)\b"#, ignoreCase: true)
    private static let gone: Set<String> = ["[deleted]", "[removed]"]

    static let minLines = 4
    static let maxLines = 150

    static func score(_ text: String) -> Int {
        if gone.contains(text.kTrimmed) { return 0 }
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
            .map(RecipeTextSplitter.cleanLine)
            .filter { !$0.isEmpty }
        if lines.isEmpty { return 0 }
        let sections = Set(lines.compactMap(RecipeTextSplitter.section))

        var score = 0
        if sections.contains(.ingredients) { score += 3 }
        if sections.contains(.instructions) { score += 3 }
        if transcription.containsMatch(in: text) { score += 2 }
        if lines.count < minLines {
            score -= 2
        } else if lines.count <= maxLines {
            score += 1
        } else {
            score -= 1
        }
        return max(0, score)
    }

    /// The best-scoring comment that splits into a recipe, or nil when none does.
    static func pick(_ comments: [String]) -> String? {
        comments.enumerated()
            .map { (index: $0.offset, text: $0.element, score: score($0.element)) }
            .filter { $0.score > 0 }
            .sorted { $0.score != $1.score ? $0.score > $1.score : $0.index < $1.index }
            .first { RecipeTextSplitter.split($0.text) != nil }?
            .text
    }
}
