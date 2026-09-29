import Foundation

/// One comment on a Reddit post: its Markdown, and whether the post's author wrote it
/// (Reddit's `is_submitter`).
struct RedditComment: Equatable {
    let body: String
    var bySubmitter = false
}

/// Picks the comment on a Reddit post most likely to be the recipe: the poster's own, else a
/// transcription. Ported from Android's `RedditCommentScorer`, whose doc comment has the rules:
///  - an ingredients header line +3, an instructions header line +3 (as `RecipeTextSplitter`
///    recognises them); an explicit mention of transcribing +2;
///  - 4 to 150 non-empty lines +1, fewer than 4 -2, more than 150 -1; never below 0;
///    "[deleted]" and "[removed]" score 0.
/// `pick` puts the poster's comments first, then ranks by score, ties to the earlier comment,
/// and returns the first that actually splits: a high score without clear structure is passed
/// over, never guessed at.
///
/// Pure: text in, text out.
enum RedditCommentScorer {

    private static let transcription = JRegex(#"\btranscri(?:be|bed|bing|ption|ptions|pt)\b"#, ignoreCase: true)
    private static let gone: Set<String> = ["[deleted]", "[removed]"]

    static let minLines = 4
    static let maxLines = 150

    static func score(_ text: String) -> Int {
        if gone.contains(text.kTrimmed) { return 0 }
        let words = RecipeTextSplitter.wordsFor(RecipeTextSplitter.languageOf(text))
        let lines = RecipeTextSplitter.lines(text, words: words).filter { !$0.text.isEmpty }
        if lines.isEmpty { return 0 }
        let sections = Set(lines.compactMap { RecipeTextSplitter.header($0, words: words)?.section })

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

    /// The best comment that splits into a recipe, the poster's first; nil when none does.
    static func pick(_ comments: [RedditComment]) -> String? {
        comments.enumerated()
            .map { (index: $0.offset, comment: $0.element, score: score($0.element.body)) }
            .filter { $0.score > 0 }
            .sorted {
                if $0.comment.bySubmitter != $1.comment.bySubmitter { return $0.comment.bySubmitter }
                return $0.score != $1.score ? $0.score > $1.score : $0.index < $1.index
            }
            .first { RecipeTextSplitter.detectAndSplit($0.comment.body) != nil }?
            .comment.body
    }
}
