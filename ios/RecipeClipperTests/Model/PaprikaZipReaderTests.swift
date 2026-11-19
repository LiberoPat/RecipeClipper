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
        XCTAssertEqual(PaprikaZipReader.read(zip(["readme.txt": Data("hi".utf8)])), .failure(.notAPaprikaFile))
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
        let archive = zip(["../../etc/passwd.paprikarecipe": gzip(json), "/abs/Soup2.paprikarecipe": gzip(json)])
        XCTAssertEqual(try contents(PaprikaZipReader.read(archive)).recipes.count, 2)
    }

    // MARK: A tiny STORED zip writer and a stored-block gzip writer, for tests that need their own bytes

    private func gzip(_ data: Data) -> Data {
        // A DEFLATE "stored" block: no compression, so no encoder is needed.
        var out = Data([0x1F, 0x8B, 8, 0, 0, 0, 0, 0, 0, 0xFF])
        out.append(contentsOf: [0x01, UInt8(data.count & 0xFF), UInt8(data.count >> 8),
                                UInt8(~data.count & 0xFF), UInt8((~data.count >> 8) & 0xFF)])
        out.append(data)
        var crc = CRC32.checksum(data).littleEndian
        out.append(Data(bytes: &crc, count: 4))
        var size = UInt32(data.count).littleEndian
        out.append(Data(bytes: &size, count: 4))
        return out
    }

    private func zip(_ entries: [String: Data]) -> Data { storedZip(entries) }

    private func storedZip(_ entries: [String: Data]) -> Data {
        func le16(_ v: Int) -> Data { Data([UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)]) }
        func le32(_ v: UInt32) -> Data { Data([UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]) }
        var out = Data()
        var central = Data()
        for (name, data) in entries.sorted(by: { $0.key < $1.key }) {
            let nameBytes = Data(name.utf8)
            let crc = CRC32.checksum(data)
            let offset = UInt32(out.count)
            out.append(le32(0x0403_4B50)); out.append(le16(20)); out.append(le16(0)); out.append(le16(0))
            out.append(le16(0)); out.append(le16(0x21)); out.append(le32(crc))
            out.append(le32(UInt32(data.count))); out.append(le32(UInt32(data.count)))
            out.append(le16(nameBytes.count)); out.append(le16(0)); out.append(nameBytes); out.append(data)
            central.append(le32(0x0201_4B50)); central.append(le16(20)); central.append(le16(20))
            central.append(le16(0)); central.append(le16(0)); central.append(le16(0)); central.append(le16(0x21))
            central.append(le32(crc)); central.append(le32(UInt32(data.count))); central.append(le32(UInt32(data.count)))
            central.append(le16(nameBytes.count)); central.append(le16(0)); central.append(le16(0))
            central.append(le16(0)); central.append(le16(0)); central.append(le32(0)); central.append(le32(offset))
            central.append(nameBytes)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.append(le32(0x0605_4B50)); out.append(le16(0)); out.append(le16(0))
        out.append(le16(entries.count)); out.append(le16(entries.count))
        out.append(le32(UInt32(central.count))); out.append(le32(centralOffset)); out.append(le16(0))
        return out
    }
}
