import Compression
import XCTest
@testable import RecipeClipper

/// Reading a Paprika export (#382; Android's PaprikaArchiveTest). The files in
/// `shared/fixtures/paprika/` are built by `tools/paprika-fixtures/make_fixtures.py` from the format
/// libpaprika documents (a zip of gzipped JSON); Android's test reads the same files and expects the
/// same counts.
final class PaprikaZipReaderTests: XCTestCase {

    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: name, withExtension: "paprikarecipes", subdirectory: "fixtures/paprika"
        ))
        return try Data(contentsOf: url)
    }

    private func contents(_ read: PaprikaRead, file: StaticString = #filePath, line: UInt = #line) throws -> PaprikaContents {
        guard case .success(let contents) = read else {
            XCTFail("expected a success, got \(read)", file: file, line: line)
            throw CancellationError()
        }
        return contents
    }

    func testAValidExportGivesItsRecipesInOrderAndCountsWhatItSkipped() throws {
        let c = try contents(PaprikaZipReader.read(fixture("paprika-valid")))
        XCTAssertEqual(c.recipes.map(\.name), ["Fudgy Brownies", "Gran's Lentil Soup", "Mint Tea", "Brownies again"])
        // The recipe with a name but no ingredients or steps, and the one with no name.
        XCTAssertEqual(c.notRecipes, 2)
        XCTAssertEqual(c.unreadable, 0)
    }

    func testEachPictureIsHandedToTheStoreAsItIsReadAndTheRecipeNamesTheFileItGot() throws {
        var seen: [Data] = []
        let read = PaprikaZipReader.read(try fixture("paprika-photos"), storePhoto: { data in
            // The store's answer: a name for a picture, nil for what isn't one.
            if String(decoding: data, as: UTF8.self).hasPrefix("bad") { return nil }
            seen.append(data)
            return "stored-\(seen.count).jpg"
        })
        let c = try contents(read)
        XCTAssertEqual(c.recipes.map(\.name), ["Toast", "Jam", "Butter", "Honey", "Salt"])
        // Only the good picture reached the store as a stored file; Honey's is over the 8 MiB and
        // Butter's isn't base64, so neither was even offered, and Jam's was refused by the store.
        XCTAssertEqual(c.recipes.map(\.photoFile), ["stored-1.jpg", nil, nil, nil, nil])
        XCTAssertEqual(seen.count, 1)
        XCTAssertEqual(c.photosLeftOut, 3)
        XCTAssertTrue(c.recipes.allSatisfy { $0.photoData == nil })
    }

    func testWithNoStoreEveryPictureIsLeftOutAndCountedTheRecipesStay() throws {
        let c = try contents(PaprikaZipReader.read(fixture("paprika-photos")))
        XCTAssertEqual(c.recipes.count, 5)
        XCTAssertEqual(c.photosLeftOut, 4)
    }

    func testALoweredPictureCapLeavesAPictureOut() throws {
        var limits = PaprikaLimits()
        limits.maxPhotoBytes = 8
        let c = try contents(PaprikaZipReader.read(fixture("paprika-photos"), limits: limits, storePhoto: { _ in "kept.jpg" }))
        // Toast's 31 bytes and Jam's 25 are over 8; the others were never offered.
        XCTAssertEqual(c.photosLeftOut, 4)
        XCTAssertTrue(c.recipes.allSatisfy { $0.photoFile == nil })
    }

    func testFoldersMacOSCopiesAndOtherFilesAreIgnoredNotCounted() throws {
        XCTAssertEqual(try contents(PaprikaZipReader.read(fixture("paprika-valid"))).unreadable, 0)
        XCTAssertFalse(PaprikaZipReader.isRecipeEntry("__MACOSX/._A.paprikarecipe"))
        XCTAssertFalse(PaprikaZipReader.isRecipeEntry("dir/__MACOSX/A.paprikarecipe"))
        XCTAssertFalse(PaprikaZipReader.isRecipeEntry("._A.paprikarecipe"))
        XCTAssertFalse(PaprikaZipReader.isRecipeEntry("readme.txt"))
        XCTAssertFalse(PaprikaZipReader.isRecipeEntry("folder/"))
        XCTAssertTrue(PaprikaZipReader.isRecipeEntry("Pasta.paprikarecipe"))
        XCTAssertTrue(PaprikaZipReader.isRecipeEntry("sub/Pasta.PAPRIKARECIPE"))
    }

    func testATruncatedZipIsDamagedAndImportsNothing() throws {
        XCTAssertEqual(PaprikaZipReader.read(try fixture("paprika-truncated")), .failure(.damaged))
    }

    func testSomethingThatIsNotAZipIsNotAPaprikaFile() {
        XCTAssertEqual(PaprikaZipReader.read(Data("Dear diary, today I made soup.".utf8)), .failure(.notAPaprikaFile))
        XCTAssertEqual(PaprikaZipReader.read(Data()), .failure(.notAPaprikaFile))
    }

    func testAZipWithNoRecipeEntriesIsNotAPaprikaFile() {
        XCTAssertEqual(PaprikaZipReader.read(PaprikaZipTestSupport.storedZip(["readme.txt": Data("hi".utf8)])), .failure(.notAPaprikaFile))
    }

    func testEntriesThatAreNotGzipNotJsonOrNotAnObjectAreCountedAndTheRestStillImport() throws {
        let c = try contents(PaprikaZipReader.read(fixture("paprika-bad-entries")))
        XCTAssertEqual(c.recipes.map(\.name), ["Mint Tea"])
        // Plain JSON (not gzipped), text, a list, and bytes that aren't UTF-8.
        XCTAssertEqual(c.unreadable, 4)
        XCTAssertEqual(c.notRecipes, 0)
    }

    func testAnEntryThatClaimsToUnpackTo4GBIsRefusedUnread() throws {
        let c = try contents(PaprikaZipReader.read(fixture("paprika-huge-declared")))
        XCTAssertEqual(c.recipes.map(\.name), ["Mint Tea"])
        XCTAssertEqual(c.unreadable, 1)
    }

    func testAnEntryThatUnpacksPastThePerEntryCapIsDroppedHoweverSmallTheFile() throws {
        let file = try fixture("paprika-bomb")
        XCTAssertLessThan(file.count, 5_000, "the bomb is tiny")
        let c = try contents(PaprikaZipReader.read(file))
        XCTAssertEqual(c.recipes.map(\.name), ["Mint Tea"])
        XCTAssertEqual(c.unreadable, 1)
    }

    func testTooManyEntriesIsRefusedBeforeAnyIsRead() throws {
        var limits = PaprikaLimits()
        limits.maxEntries = 3
        XCTAssertEqual(PaprikaZipReader.read(try fixture("paprika-valid"), limits: limits), .failure(.tooLarge))
    }

    func testMoreUnpackedTextThanTheTotalCapIsRefusedAndNothingIsImported() throws {
        var limits = PaprikaLimits()
        limits.maxTotalBytes = 600
        XCTAssertEqual(PaprikaZipReader.read(try fixture("paprika-valid"), limits: limits), .failure(.tooLarge))
    }

    func testALoweredEntryCapDropsTheRecipesOverIt() throws {
        var limits = PaprikaLimits()
        limits.maxEntryBytes = 400
        let c = try contents(PaprikaZipReader.read(fixture("paprika-valid"), limits: limits))
        XCTAssertGreaterThan(c.unreadable, 0)
        XCTAssertEqual(c.recipes.count + c.notRecipes + c.unreadable, 6)
    }

    func testAnEntryNameIsOnlyComparedSoAPathNameDoesNothing() throws {
        let json = Data(#"{"name": "Soup", "ingredients": "1 onion"}"#.utf8)
        let archive = PaprikaZipTestSupport.storedZip(["../../etc/passwd.paprikarecipe": PaprikaZipTestSupport.gzip(json), "/abs/Soup2.paprikarecipe": PaprikaZipTestSupport.gzip(json)])
        XCTAssertEqual(try contents(PaprikaZipReader.read(archive)).recipes.count, 2)
    }
}
