import SwiftUI

/// The Text view (#213), line by line since #286 (Android's ClipTextView): the post's lines as
/// rows, each with a checkbox. A ticked line is washed in paprika; the ticked lines are the
/// selection, so the dock counts them and a field takes them (armed, through the confirm; or a
/// field's button straight away). A line an add took keeps a paprika bar, and the add's first
/// line its tag, which takes the add back, as on the page. The rows and ticks are the
/// ViewModel's.
struct ClipTextView: View {
    let vm: ClipViewModel

    /// The ticked lines' wash: the app's paprika wash (as a step's ingredient link, #254).
    private static let washOpacity = 0.14

    var body: some View {
        let state = vm.uiState
        let marks = state.textMarks
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(state.textRows) { row in
                    switch row {
                    case .line(let index, let text, let title, let depth):
                        lineRow(index, text, title: title, ticked: state.ticked.contains(index), mark: marks[index])
                            .thread(depth)
                    case .comments:
                        commentsHeading
                    case .author(_, let name, let depth):
                        Text(Strings.clipTextAuthor(name))
                            .textStyle(Typography.labelLarge)
                            .foregroundStyle(Palette.muted)
                            .padding(.top, 14)
                            .padding(.bottom, 4)
                            .thread(depth)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(Palette.background)
    }

    private var commentsHeading: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(Strings.clipTextComments)
                .textStyle(Typography.titleSmall)
                .foregroundStyle(Palette.onBackground)
                .accessibilityAddTraits(.isHeader)
            Text(Strings.clipTextLoadedNote)
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 24)
        .padding(.bottom, 2)
    }

    /// A line: the whole row ticks its checkbox (a selected-or-not button to VoiceOver and the
    /// tests, as every tick in the app); ticked, it is washed in paprika. A line an add took has
    /// a paprika bar, and its add's first line the tag.
    private func lineRow(_ index: Int, _ text: String, title: Bool, ticked: Bool, mark: ClipTextMark?) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button { vm.onTextLine(index) } label: {
                    HStack(alignment: .top, spacing: 12) {
                        CheckboxGlyph(checked: ticked)
                        Text(text)
                            .textStyle(title ? Typography.bodyLargeBold : Typography.bodyLarge)
                            .foregroundStyle(Palette.onBackground)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 9)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(ticked ? [.isSelected] : [])
                .accessibilityIdentifier("clip.line.\(index)")
                if let mark, mark.first {
                    Button(Strings.clipTag(mark.mark)) { vm.onTagTapped(mark.mark.id) }
                        .buttonStyle(ClipTagStyle())
                        .accessibilityIdentifier("clip.tag.\(mark.mark.id)")
                }
            }
            .padding(.leading, 8)
            .padding(.trailing, 4)
            .background(ticked ? Palette.accentText.opacity(Self.washOpacity) : Color.clear)
            .overlay(alignment: .leading) {
                if mark != nil { Rectangle().fill(Palette.primary).frame(width: 3) }
            }
            Hairline()
        }
    }
}

/// An add's tag on a line, as the page draws it: paprika, white, small; a roomier tap target.
private struct ClipTagStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(Typography.labelMedium)
            .fontWeight(.semibold)
            .foregroundStyle(Palette.onPrimary)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 10).fill(Palette.primary))
            .padding(.vertical, 8)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

private extension View {
    /// A comment's rows: indented by its depth, with the thread's hairline down their left.
    @ViewBuilder func thread(_ depth: Int?) -> some View {
        if let depth {
            padding(.leading, 10)
                .overlay(alignment: .leading) { Rectangle().fill(Palette.hairline).frame(width: 2) }
                .padding(.leading, CGFloat(depth * 12))
        } else {
            self
        }
    }
}
