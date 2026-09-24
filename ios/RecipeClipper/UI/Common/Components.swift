import SwiftUI

/// A 1pt divider in the hairline token.
struct Hairline: View {
    var body: some View {
        Rectangle().fill(Palette.hairline).frame(height: 1).frame(maxWidth: .infinity)
    }
}

struct SectionHeading: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .textStyle(Typography.titleMedium)
            .foregroundStyle(Palette.onBackground)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A large Fraunces title in the content, under the native navigation bar.
struct ScreenTitle: View {
    let text: String
    var style: TextStyle = Typography.headlineMedium
    init(_ text: String, style: TextStyle = Typography.headlineMedium) {
        self.text = text
        self.style = style
    }
    var body: some View {
        Text(text)
            .textStyle(style)
            .foregroundStyle(Palette.onBackground)
            // Titles of several words wrap. A single long word ("Einstellungen" at the
            // largest sizes) would break mid-word instead, so it shrinks to fit its line.
            .lineLimit(text.contains(" ") ? nil : 1)
            .minimumScaleFactor(0.5)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Draws a checkbox's state; the row around it is the tap target.
struct CheckboxGlyph: View {
    let checked: Bool
    /// Grows with the text beside it, up to half again: a checkbox the size of an AX5 cap
    /// height would only crowd the text it marks.
    @ScaledMetric(relativeTo: .body) private var scaledSize: CGFloat = 20
    private var size: CGFloat { min(scaledSize, 30) }
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4)
                .strokeBorder(checked ? Palette.primary : Palette.muted, lineWidth: 2)
                .background(RoundedRectangle(cornerRadius: 4).fill(checked ? Palette.primary : .clear))
            if checked {
                Image(systemName: "checkmark")
                    .font(.system(size: size * 0.6, weight: .bold))
                    .foregroundStyle(Palette.onPrimary)
            }
        }
        .frame(width: size, height: size)
        .padding(2)
        .accessibilityHidden(true)
    }
}

/// Draws a radio button's state; the row around it is the tap target.
struct RadioGlyph: View {
    let selected: Bool
    @ScaledMetric(relativeTo: .body) private var scaledSize: CGFloat = 20
    private var size: CGFloat { min(scaledSize, 30) }
    var body: some View {
        ZStack {
            Circle().strokeBorder(selected ? Palette.primary : Palette.muted, lineWidth: 2)
            if selected { Circle().fill(Palette.primary).padding(size / 4) }
        }
        .frame(width: size, height: size)
        .padding(2)
        .accessibilityHidden(true)
    }
}

/// A whole-row tap target: the checkbox only draws the state, the row toggles it.
struct IngredientRow: View {
    let text: String
    let checked: Bool
    let onCheckedChange: (Bool) -> Void

    var body: some View {
        Button { onCheckedChange(!checked) } label: {
            HStack(alignment: .top, spacing: 12) {
                CheckboxGlyph(checked: checked)
                Text(text)
                    .textStyle(Typography.bodyLarge)
                    .strikethrough(checked)
                    .foregroundStyle(checked ? Palette.muted : Palette.onBackground)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? [.isSelected] : [])
    }
}

/// A label, a chevron, and a hairline under it: Home's History / Lists block and the Lists rows.
struct NavRow<Subtitle: View>: View {
    let title: String
    let action: () -> Void
    @ViewBuilder var subtitle: () -> Subtitle

    var body: some View {
        Button(action: action) {
            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(title).textStyle(Typography.bodyLarge).foregroundStyle(Palette.onBackground)
                        subtitle()
                    }
                    Spacer()
                    Text("›").textStyle(Typography.titleLarge).foregroundStyle(Palette.muted)
                }
                .padding(.vertical, 14)
                .contentShape(Rectangle())
                Hairline()
            }
        }
        .buttonStyle(.plain)
    }
}

extension NavRow where Subtitle == EmptyView {
    init(title: String, action: @escaping () -> Void) {
        self.init(title: title, action: action, subtitle: { EmptyView() })
    }
}

/// The filled paprika button (Material's Button with the primary slot).
struct PrimaryButtonStyle: ButtonStyle {
    var minHeight: CGFloat = 44
    var fillWidth = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(Typography.labelLarge)
            .foregroundStyle(Palette.onPrimary)
            .padding(.horizontal, 20)
            .wrappedLabelPadding(8, alignment: .center)
            .frame(maxWidth: fillWidth ? .infinity : nil)
            .frame(minHeight: minHeight)
            .background(RoundedRectangle(cornerRadius: 14).fill(Palette.primary))
            .opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4)
    }
}

/// A text button in the accent colour (Material's TextButton).
struct TextActionStyle: ButtonStyle {
    var color: Color = Palette.accentText
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(Typography.labelLarge)
            .foregroundStyle(color)
            .padding(.horizontal, 12)
            .wrappedLabelPadding(6)
            .frame(minHeight: 40)
            .contentShape(Rectangle())
            .opacity(isEnabled ? (configuration.isPressed ? 0.6 : 1) : 0.4)
    }
}

/// An outlined text field with a floating-style label above it and an optional error line.
struct OutlinedField: View {
    let label: String
    @Binding var text: String
    var isError = false
    var errorText: String? = nil
    var keyboard: UIKeyboardType = .default
    var onSubmit: () -> Void = {}
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            TextField(label, text: $text)
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.onBackground)
                .keyboardType(keyboard)
                .textInputAutocapitalization(keyboard == .URL ? .never : .sentences)
                .autocorrectionDisabled(keyboard == .URL)
                .submitLabel(.go)
                .focused($focused)
                .onSubmit(onSubmit)
                .padding(.horizontal, 14)
                .wrappedLabelPadding(10)
                .frame(minHeight: 52)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(
                            isError ? Palette.error : (focused ? Palette.primary : Palette.outline),
                            lineWidth: focused || isError ? 2 : 1
                        )
                )
            if isError, let errorText {
                Text(errorText)
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.error)
                    .padding(.horizontal, 14)
            }
        }
    }
}

/// "+ New list" expanding inline to a field with Cancel / Create — never a dialog stacked on
/// a sheet. Shared by the Lists screen and the save-to-list sheet.
struct NewListControl: View {
    let creating: Bool
    let name: String
    let onStart: () -> Void
    let onNameChange: (String) -> Void
    let onCancel: () -> Void
    let onCreate: () -> Void

    var body: some View {
        if creating {
            VStack(alignment: .trailing, spacing: 4) {
                OutlinedField(
                    label: Strings.listName,
                    text: Binding(get: { name }, set: onNameChange),
                    onSubmit: onCreate
                )
                // Side by side when they fit; stacked at the accessibility sizes, where two
                // words at ~50pt no longer share a line.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 4) { cancelButton.fixedSize(); createButton.fixedSize() }
                    VStack(alignment: .trailing, spacing: 0) { createButton; cancelButton }
                }
            }
        } else {
            Button(Strings.newList, action: onStart)
                .buttonStyle(TextActionStyle())
                .padding(.leading, -12)
        }
    }
}

private extension NewListControl {
    var cancelButton: some View {
        Button(Strings.cancel, action: onCancel).buttonStyle(TextActionStyle())
    }
    var createButton: some View {
        Button(Strings.create, action: onCreate)
            .buttonStyle(TextActionStyle())
            .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
}

/// Keeps one ViewModel per navigation entry. The factory runs on first render only, so a
/// parent re-render never builds (and re-loads) a second ViewModel — the SwiftUI counterpart
/// of a ViewModel scoped to its back-stack entry.
struct ScreenHost<VM: AnyObject, Content: View>: View {
    private final class Box { var value: VM? }
    @State private var box = Box()
    let make: () -> VM
    @ViewBuilder let content: (VM) -> Content

    init(_ make: @escaping () -> VM, @ViewBuilder content: @escaping (VM) -> Content) {
        self.make = make
        self.content = content
    }

    var body: some View {
        content(resolve())
    }

    private func resolve() -> VM {
        if let vm = box.value { return vm }
        let vm = make()
        box.value = vm
        return vm
    }
}

/// Two ViewModels for one entry (the recipe screen holds a recipe and a save-to-list one).
struct ScreenHost2<A: AnyObject, B: AnyObject, Content: View>: View {
    private final class Box { var a: A?; var b: B? }
    @State private var box = Box()
    let makeA: () -> A
    let makeB: () -> B
    @ViewBuilder let content: (A, B) -> Content

    var body: some View {
        let a = box.a ?? { let v = makeA(); box.a = v; return v }()
        let b = box.b ?? { let v = makeB(); box.b = v; return v }()
        content(a, b)
    }
}

/// Vertical padding for a control whose label sits inside a min height. At the default sizes
/// the min height already clears the label, and padding there would only move it by a
/// sub-pixel rounding step; at the accessibility sizes, where a label can wrap and outgrow
/// the min height, it keeps the text off the control's edges.
private struct WrappedLabelPadding: ViewModifier {
    let amount: CGFloat
    let alignment: TextAlignment?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    func body(content: Content) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            content
                .multilineTextAlignment(alignment ?? .leading)
                .padding(.vertical, amount)
        } else {
            content
        }
    }
}

extension View {
    /// Also sets how a wrapped label aligns — only at those sizes, since even an unused
    /// alignment moves single-line text by a sub-pixel at the default size.
    func wrappedLabelPadding(_ amount: CGFloat, alignment: TextAlignment? = nil) -> some View {
        modifier(WrappedLabelPadding(amount: amount, alignment: alignment))
    }

    /// The screen ground behind everything, edge to edge.
    func screenBackground() -> some View {
        background(Palette.background.ignoresSafeArea())
    }

    /// Caps content that already carries its side gutters at the readable column and centres
    /// it, so text never runs edge to edge on an iPad (or an iPhone in landscape). Narrower
    /// than the cap, as every iPhone is in portrait, both frames resolve to the proposed
    /// width and nothing moves. Put it on the content, not on the scroll view, so the margins
    /// still scroll.
    func readableColumn() -> some View {
        frame(maxWidth: ReadableWidth.cappedFrame).frame(maxWidth: .infinity)
    }
}

/// The reading-width cap for wide screens (issue #20).
enum ReadableWidth {
    /// The widest a line of text gets, in points.
    static let column: CGFloat = 680
    /// The side gutter every screen already pads its content with.
    static let gutter: CGFloat = 20
    /// The column plus both gutters: the frame `readableColumn()` caps padded content at.
    static let cappedFrame = column + 2 * gutter

    /// The side inset that centres the column in `width`, never less than the gutter. For a
    /// `List`, whose rows take insets rather than a frame.
    static func inset(in width: CGFloat) -> CGFloat {
        max(gutter, (width - column) / 2)
    }
}

/// An outlined button (Material's OutlinedButton): the cook view's "Start timer".
struct OutlinedActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .textStyle(Typography.labelLarge)
            .foregroundStyle(Palette.onBackground)
            .padding(.horizontal, 16)
            .wrappedLabelPadding(8)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.outline, lineWidth: 1))
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}
