import Foundation
@testable import RecipeClipper

/// Answers with what the test staged (#149), recording what it was asked.
final class FakeShareFileRepository: ShareFileRepository {
    /// The file text per recipe id; a missing one reads as gone.
    var recipeFiles: [Int64: String] = [:]
    /// The grocery list's file; nil when nothing is left to buy.
    var groceriesFileText: String?
    /// The pantry's file; nil when nothing is in stock.
    var pantryFileText: String?
    var receiveResult: Result<ImportSummary, BackupError> =
        .success(ImportSummary(recipesAdded: 0, listsAdded: 0, recipesAlreadyHere: 0, recipesSkipped: 0))
    /// Every `receive` call's choice.
    private(set) var received: [ShareChoice] = []

    func recipeFile(recipeId: Int64) async -> String? { recipeFiles[recipeId] }

    func groceriesFile() async -> String? { groceriesFileText }

    func pantryFile() async -> String? { pantryFileText }

    func receive(_ file: Backup, choice: ShareChoice) async -> Result<ImportSummary, BackupError> {
        received.append(choice)
        return receiveResult
    }
}
