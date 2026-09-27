import XCTest
@testable import RecipeClipper

/// The export with photos (#116; Android's BackupArchiveTest, against the same shared zip,
/// written by a third tool): it reads here; a zip this app writes reads back; plain JSON is not
/// a zip; and the merge takes a photo only with its picture, its recipe counting as listed.
final class BackupArchiveTests: XCTestCase {

    private func fixtureZip() throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "backup-v1-photos", withExtension: "zip", subdirectory: "fixtures/backup"
        ))
        return try Data(contentsOf: url)
    }

    private func read(_ zip: Data, max: Int = 1_000_000) -> Result<BackupPackage, BackupError> {
        BackupArchive.read(zip, photoDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString), maxJsonBytes: max)
    }

    private func decoded(_ json: String) throws -> Backup {
        switch BackupJson.decode(json) {
        case .success(let backup): return backup
        case .failure(let error): XCTFail("decode failed: \(error)"); throw error
        }
    }

    func testTheSharedZipFixtureReadsItsJsonAndItsPhotos() throws {
        let zip = try fixtureZip()
        XCTAssertTrue(BackupArchive.isZip(zip))
        guard case .success(let package) = read(zip) else { return XCTFail("fixture didn't read") }
        XCTAssertEqual(Set(package.photos.keys), ["photos/p-first.jpg", "photos/p-second.jpg", "photos/p-orphan.jpg"])
        XCTAssertEqual(try Data(contentsOf: package.photos["photos/p-first.jpg"]!), Data("first picture".utf8))

        let backup = try decoded(package.json)
        XCTAssertEqual(backup.cookedPhotos.map(\.id), ["p-first", "p-second", "p-nopicture"])
        XCTAssertEqual(backup.cookedPhotos[0], BackupCookedPhoto(
            id: "p-first", recipeId: "r-soup", day: 20_000, note: "Less salt next time",
            createdAt: 1789000000600, updatedAt: 1789000000700, file: "photos/p-first.jpg"
        ))
        XCTAssertNil(backup.cookedPhotos[1].note)
    }

    func testTheMergeTakesAPhotoOnlyWithItsPictureAndItsRecipeCountsAsListed() throws {
        guard case .success(let package) = read(try fixtureZip()) else { return XCTFail("fixture didn't read") }
        let backup = try decoded(package.json)
        let plan = BackupMerger.plan(
            backup, existingRecipes: [], existingLists: [], maxSortOrder: 0, historyLimit: 0, newUid: { "fresh" },
            availablePhotoFiles: Set(package.photos.keys)
        )
        XCTAssertEqual(plan.newRecipes.map(\.id), ["r-soup"])
        XCTAssertEqual(plan.newCookedPhotos.map(\.photo.id), ["p-first", "p-second"])
        XCTAssertEqual(plan.summary.photosAdded, 2)

        let again = BackupMerger.plan(
            backup, existingRecipes: [], existingLists: [], maxSortOrder: 0, historyLimit: 0, newUid: { "fresh" },
            existingCookedPhotoUids: ["p-first"], availablePhotoFiles: []
        )
        XCTAssertTrue(again.newCookedPhotos.isEmpty)
        XCTAssertTrue(again.newRecipes.isEmpty)
    }

    func testAZipThisAppWritesReadsBackAndPlainJsonIsNotAZip() throws {
        let picture = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        try Data("bytes".utf8).write(to: picture)
        let zip = BackupArchive.write(json: "{\"x\":1}", photos: [(path: "photos/a.jpg", file: picture), (path: "../evil.jpg", file: picture)])
        guard case .success(let package) = read(zip) else { return XCTFail("zip didn't read") }
        XCTAssertEqual(package.json, "{\"x\":1}")
        XCTAssertEqual(Set(package.photos.keys), ["photos/a.jpg"])
        XCTAssertFalse(BackupArchive.isZip(Data("{\"format\"".utf8)))
        XCTAssertEqual(read(zip, max: 1), .failure(.notABackup))
        XCTAssertEqual(read(Data("PK\u{3}\u{4}garbage".utf8)), .failure(.notABackup))
    }

    func testAPhotosFileMustBeAPlainNameUnderPhotos() {
        let bad = """
            {"format":"recipe-clipper-backup","formatVersion":1,"exportedAt":1,"recipes":[
            {"id":"r","sourceUrl":"https://a.com","sourceType":"BLOG","title":"T","ingredients":["x"],"instructions":[],"lastViewedAt":1}],
            "cookedPhotos":[{"id":"p","recipeId":"r","day":1,"file":"photos/../../x.jpg"}]}
            """
        XCTAssertEqual(BackupJson.decode(bad), .failure(.malformed("cookedPhotos[0].file")))
    }
}
