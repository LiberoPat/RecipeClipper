import XCTest
@testable import RecipeClipper

/// `NotesAndTicks` on its own (#234; Android's NotesAndTicksTest): ticks written as they change,
/// the note once typing pauses, or when it goes with its screen.
@MainActor
final class NotesAndTicksTests: XCTestCase {
    private let repository = FakeRecipeRepository()
    private let clock = TestClock(now: 0)

    private func writes() -> NotesAndTicks {
        NotesAndTicks(repository: repository, sleep: clock.sleep, saveDelay: .milliseconds(500))
    }

    func testEveryTickIsWrittenAsItChanges() async {
        let writes = writes()

        writes.ticked(id: 5, checked: [1])
        writes.ticked(id: 5, checked: [1, 2])
        await settleMain()

        XCTAssertEqual(repository.setCheckedCalls.map(\.id), [5, 5])
        XCTAssertEqual(repository.setCheckedCalls.map(\.checked), [[1], [1, 2]])
    }

    func testTheNoteIsWrittenOnceAfterTypingPauses() async {
        let writes = writes()

        writes.noteChanged(id: 5, text: "N")
        await clock.advance(by: 499)
        writes.noteChanged(id: 5, text: "Ne")
        await clock.advance(by: 499)
        XCTAssertTrue(repository.setNotesCalls.isEmpty)

        await clock.advance(by: 2)
        await settleMain()
        XCTAssertEqual(repository.setNotesCalls.map(\.notes), ["Ne"])
        XCTAssertEqual(repository.setNotesCalls.map(\.id), [5])
    }

    func testANoteStillWaitingIsWrittenWhenItsScreenGoes() async {
        var writes: NotesAndTicks? = writes()

        writes?.noteChanged(id: 5, text: "Less salt")
        writes = nil
        await settleMain()

        XCTAssertEqual(repository.setNotesCalls.map(\.notes), ["Less salt"])
    }

    func testANoteAlreadyWrittenIsNotWrittenAgainWhenItsScreenGoes() async {
        var writes: NotesAndTicks? = writes()

        writes?.noteChanged(id: 5, text: "Less salt")
        await clock.advance(by: 500)
        await settleMain()
        writes = nil
        await settleMain()

        XCTAssertEqual(repository.setNotesCalls.map(\.notes), ["Less salt"])
    }
}
