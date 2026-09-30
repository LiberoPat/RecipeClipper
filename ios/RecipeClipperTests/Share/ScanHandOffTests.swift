import UniformTypeIdentifiers
import XCTest
@testable import RecipeClipper

/// Images shared in (#226): the share extension copies them as a scan's pages and leaves them for
/// the app (`PendingScan`), which opens the review within the window, once. Also `ScanPages`, the
/// pages on disk the app's own camera and library scans use.
@MainActor
final class ScanHandOffTests: XCTestCase {
    private var directory: URL!
    private var pages: ScanPages!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("ScanHandOff-\(UUID().uuidString)")
        pages = ScanPages(directory: directory)
        defaults = UserDefaults(suiteName: "ScanHandOff-\(UUID().uuidString)")!
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private func card(_ name: String) throws -> URL {
        try XCTUnwrap(Bundle(for: ScanHandOffTests.self)
            .url(forResource: name, withExtension: "jpg", subdirectory: "fixtures/reddit/photos"))
    }

    private func imageProvider(_ file: URL) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.registerFileRepresentation(forTypeIdentifier: UTType.jpeg.identifier, visibility: .all) { completion in
            completion(file, false, nil)
            return nil
        }
        return provider
    }

    func testStagingReplacesTheLastScanKeepingTheOrder() throws {
        let first = pages.stage([Data("one".utf8)])
        XCTAssertEqual(first.count, 1)

        let staged = pages.stage([Data("front".utf8), Data("back".utf8)])

        XCTAssertEqual(try staged.map { try Data(contentsOf: XCTUnwrap(URL(string: $0))) }, [Data("front".utf8), Data("back".utf8)])
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(URL(string: first[0])).path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path).count, 2)
    }

    func testAtMostSixPagesAreStaged() {
        XCTAssertEqual(pages.stage((1...9).map { Data("\($0)".utf8) }).count, ScanPages.maxPages)
    }

    func testTheSweepRemovesOnlyOldPages() throws {
        let staged = pages.stage([Data("page".utf8)])
        pages.sweep(olderThan: 3600)
        XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(URL(string: staged[0])).path))

        pages.sweep(olderThan: 3600, now: Date().addingTimeInterval(2 * 3600))
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(URL(string: staged[0])).path))
    }

    func testSharedImagesAreCopiedInOrderWithoutAnyOtherItem() async throws {
        let text = NSItemProvider(item: "hello" as NSString, typeIdentifier: UTType.plainText.identifier)
        let providers = [imageProvider(try card("card-front")), text, imageProvider(try card("card-back"))]

        let staged = await SharedItems.images(from: providers, into: pages)

        XCTAssertEqual(staged.count, 2)
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(URL(string: staged[0]))), try Data(contentsOf: card("card-front")))
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(URL(string: staged[1]))), try Data(contentsOf: card("card-back")))
    }

    func testAShareWithNoImageStagesNothing() async {
        let text = NSItemProvider(item: "hello" as NSString, typeIdentifier: UTType.plainText.identifier)
        let staged = await SharedItems.images(from: [text], into: pages)
        XCTAssertEqual(staged, [])
    }

    func testImagesSharedInAreLeftForTheAppWhichOpensThemOnceWithinTheWindow() async throws {
        let pending = PendingScan(defaults: defaults, pages: pages)
        let clips = PendingClip(defaults: defaults)
        clips.put("https://www.reddit.com/r/x/comments/1/", at: 1_000)
        let staged = pages.stage([Data("front".utf8), Data("back".utf8)])
        let vm = ShareImportViewModel(repository: nil, pendingClip: clips, pendingScan: pending, clock: DataTestClock(5_000))

        vm.start(with: nil, scanPages: staged)

        XCTAssertEqual(vm.uiState, .scanInApp)
        XCTAssertNil(clips.take(now: 5_000), "a new share moves on from a post left before")
        XCTAssertEqual(pending.take(now: 5_000 + PendingScan.window), staged)
        XCTAssertNil(pending.take(now: 5_000 + PendingScan.window), "taken once")
    }

    func testPagesLeftTooLongAgoAreDropped() {
        let pending = PendingScan(defaults: defaults, pages: pages)
        pending.put(pages.stage([Data("page".utf8)]), at: 1_000)
        XCTAssertNil(pending.take(now: 1_000 + PendingScan.window + 1))
    }

    func testALinkSharedLaterClearsTheImagesLeftBefore() async {
        let pending = PendingScan(defaults: defaults, pages: pages)
        pending.put(pages.stage([Data("page".utf8)]), at: 1_000)
        let repository = ReconnectCountingRepository(.success(Recipe(
            name: "Guacamole", image: nil, ingredients: ["3 avocados"], instructions: ["Mash."], prepTime: nil,
            cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/guacamole", id: 7
        )))
        let vm = ShareImportViewModel(repository: repository, pendingScan: pending, clock: DataTestClock(2_000))

        vm.start(with: SharedInput(url: "https://example.com/guacamole"))
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Guacamole"))
        XCTAssertNil(pending.take(now: 2_000))
    }

    func testWithNoPlaceToLeaveThemImagesAreNotALink() {
        let vm = ShareImportViewModel(repository: nil)
        vm.start(with: nil, scanPages: ["file:///tmp/page.img"])
        XCTAssertEqual(vm.uiState, .noLink)
    }

    func testTheSwitchTheExtensionReadsIsOnUntilTheAppWritesIt() {
        let photoText = DefaultsPhotoTextSwitch(defaults: defaults)
        XCTAssertTrue(photoText.isOn)
        photoText.store(false)
        XCTAssertFalse(photoText.isOn)
    }
}
