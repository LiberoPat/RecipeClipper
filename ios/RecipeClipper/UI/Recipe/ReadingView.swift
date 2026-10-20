import SwiftUI

/// The reading view opens on the recipe: photo, title, times, one servings-and-units row,
/// ingredients. Cooking is one bottom button.
struct ReadingView: View {
    let content: RecipeSuccess
    let state: RecipeUiState
    let vm: RecipeViewModel
    /// "Your cooks" (#116); nil leaves it out.
    var photos: CookedPhotosViewModel? = nil
    /// The step-number column grows with the numbers in it (titleMedium follows .headline).
    @ScaledMetric(relativeTo: .headline) private var numberColumn: CGFloat = 32
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// Only whether the keyboard is up for the note, so the cooking bar steps aside for it.
    @FocusState private var editingNotes: Bool

    var body: some View {
        let recipe = content.recipe
        // Credited only when there is both a domain to name and a link to open.
        let sourceUrl = content.sourceDomain == nil ? nil : URL(string: recipe.sourceUrl)
        let jump = state.ingredientJump
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let image = recipe.image, let url = URL(string: image) {
                    RecipePhoto(url: url, name: recipe.name)
                        .padding(.bottom, 16)
                }
                Text(recipe.name)
                    .textStyle(Typography.headlineSmall)
                    .foregroundStyle(Palette.onBackground)
                    .padding(.bottom, sourceUrl == nil ? 14 : 0)
                if let domain = content.sourceDomain, let sourceUrl {
                    SourceCredit(domain: domain, url: sourceUrl, clipped: recipe.origin == .clipped)
                        .padding(.bottom, 4)
                    // Picked by the on-device model (#103): every line is on the page, but which
                    // lines were picked is the model's call, so say so, quietly.
                    if recipe.origin == .extracted {
                        Text(Strings.extractedFromPage)
                            .textStyle(Typography.bodySmall)
                            .foregroundStyle(Palette.muted)
                            .padding(.bottom, 4)
                            .accessibilityIdentifier("recipe.extractedFromPage")
                    }
                }
                Times(prep: recipe.prepTime, cook: recipe.cookTime, total: recipe.totalTime)
                    .padding(.bottom, 16)
                ServesUnitsRow(
                    servings: content.servings,
                    yieldText: recipe.yield,
                    words: content.words,
                    unitSystem: state.unitSystem,
                    onServingsChange: vm.onServingsChange,
                    onUnitSystemChange: vm.onUnitSystemChange
                )
                .padding(.bottom, 20)

                SectionHeading(Strings.headingIngredients).padding(.bottom, 6)
                ForEach(Array(content.ingredients.enumerated()), id: \.offset) { index, ingredient in
                    if content.isHeading(index) {
                        IngredientHeadingRow(text: ingredient)
                    } else {
                        IngredientRow(
                            text: ingredient,
                            checked: state.checkedIngredients.contains(index),
                            onCheckedChange: { vm.onIngredientChecked(index, $0) },
                            highlighted: jump?.highlighted == true && jump?.lines.contains(index) == true
                        )
                        .id("line-\(index)")
                    }
                }

                SectionHeading(Strings.headingInstructions)
                    .padding(.top, 24)
                    .padding(.bottom, 6)
                ForEach(Array(content.instructions.indices), id: \.self) { index in
                    // Chef mode (#100): a step with a short version shows it; a tap shows it as written.
                    let step = content.shownStep(index, asWritten: state.asWrittenSteps)
                    // The number column grows with the text up to the largest standard size.
                    // At the accessibility sizes a column wide enough for "12" at ~60pt would
                    // take a third of the screen from the step, so the number goes above it.
                    let stacked = dynamicTypeSize.isAccessibilitySize
                    let layout = stacked
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
                        : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 0))
                    layout {
                        Text("\(index + 1)")
                            .textStyle(Typography.titleMedium)
                            .foregroundStyle(Palette.accentText)
                            .frame(width: stacked ? nil : numberColumn, alignment: .leading)
                        let parts = content.shownStepParts(index, asWritten: state.asWrittenSteps)
                        stepText(step, parts, accent: Palette.accentText, linked: true)
                            .textStyle(Typography.bodyLarge)
                            .foregroundStyle(Palette.onBackground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .modifier(StepLinks(parts: parts, onLink: { vm.onIngredientLink(index, $0) }))
                    }
                    .id("step-\(index)")
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                    .modifier(ShortStepToggle(
                        enabled: content.hasShortStep(index),
                        asWritten: state.asWrittenSteps.contains(index),
                        toggle: { vm.onStepAsWrittenToggle(index) }
                    ))
                }

                // After the steps: the reading view still opens on the recipe, and a note like
                // "needs 10 more minutes" is read once the method is.
                // A recipe that wasn't kept (#107) has nowhere to keep a note.
                if !state.notKept {
                    NotesSection(
                        notes: Binding(get: { state.notes }, set: { vm.onNotesChange($0) }),
                        focused: $editingNotes
                    )
                    .padding(.top, 24)
                }
                // "Your cooks" (#116): last, so the reading view still opens on the recipe.
                if !state.notKept, let photos {
                    CookedPhotosSection(vm: photos).padding(.top, 28)
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.interactively)
        // A step's ingredient link (#254): scroll to its lines, or back to the step.
        .onChange(of: jump?.scrollTo, initial: true) { _, scroll in
            guard let jump, let scroll else { return }
            withAnimation {
                switch scroll {
                case .lines: proxy.scrollTo("line-\(jump.lines.min() ?? 0)", anchor: .center)
                case .step: proxy.scrollTo("step-\(jump.step)", anchor: .center)
                }
            }
            vm.onIngredientJumpScrolled()
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !content.instructions.isEmpty && !editingNotes {
                VStack(spacing: 0) {
                    if let jump, jump.scrollTo != .step {
                        BackToStepChip(step: jump.step, action: vm.onBackToStep)
                            .padding(.bottom, 10)
                    }
                    VStack(spacing: 0) {
                        Hairline()
                        Button(Strings.startCooking, action: vm.onCookStart)
                            .buttonStyle(PrimaryButtonStyle(minHeight: 52, fillWidth: true))
                            .tooltipAnchor(.recipeStartCooking)
                            .padding(.horizontal, 20)
                            .readableColumn()
                            .padding(.vertical, 12)
                    }
                    .background(Palette.background)
                }
            }
        }
        }
    }
}

/// The user's own note, edited in place. No Save button and no sheet: every keystroke goes to
/// the ViewModel, which writes it once typing pauses. An empty note is just the quiet
/// "Add a note" prompt, so a recipe without one carries no extra chrome.
private struct NotesSection: View {
    @Binding var notes: String
    var focused: FocusState<Bool>.Binding

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeading(Strings.headingNotes).padding(.bottom, 6)
            TextField(
                Strings.headingNotes,
                text: $notes,
                prompt: Text(Strings.notesPlaceholder).foregroundStyle(Palette.muted),
                axis: .vertical
            )
            .textStyle(Typography.bodyLarge)
            .foregroundStyle(Palette.onBackground)
            .tint(Palette.primary)
            .textInputAutocapitalization(.sentences)
            .focused(focused)
            .frame(minHeight: 44)
            .padding(.vertical, 6)
            .accessibilityIdentifier("recipeNotes")
            Hairline()
        }
    }
}

private struct RecipePhoto: View {
    let url: URL
    let name: String

    var body: some View {
        Rectangle()
            .fill(Palette.hairline)
            .frame(height: 200)
            .overlay {
                CachedAsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Color.clear
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .accessibilityLabel(name)
    }
}

/// Credits the site under the title: its domain in muted text, then "Open original" as a quiet
/// paprika link to the page in the browser. Reading view only; cook mode has no room for it.
/// Opening the page is a platform effect, so it happens here through `openURL`, not in the
/// ViewModel.
private struct SourceCredit: View {
    let domain: String
    let url: URL
    /// A clip (#37) says whose selection it is, so a difference from the page, and a re-share
    /// that doesn't refresh it, both make sense.
    var clipped = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        // Side by side while both fit; stacked at the accessibility sizes, rather than
        // truncating the domain to a few letters.
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .center, spacing: 14) {
                domainText
                openButton
            }
            VStack(alignment: .leading, spacing: 0) {
                domainText
                openButton
            }
        }
    }

    private var domainText: some View {
        Text(clipped ? Strings.clippedByYou(on: domain) : domain)
            .textStyle(Typography.bodyMedium)
            .foregroundStyle(Palette.muted)
            .accessibilityIdentifier("recipe.sourceDomain")
    }

    private var openButton: some View {
        Button {
            openURL(url)
        } label: {
            Text(Strings.openOriginal)
                .textStyle(Typography.labelLarge)
                .foregroundStyle(Palette.accentText)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(Strings.openOriginalHint(domain))
        .accessibilityIdentifier("recipe.openOriginal")
    }
}

/// Plain labeled numbers on one line (#286), "Prep 35m · Cook 40m · Total 1h 15m": labels
/// muted, values bold. Deliberately not chips: chips imply tappable, these aren't.
///
/// One text, so it wraps at the accessibility sizes or in a long language (German's
/// "Vorbereitung"), but only between entries: each entry's spaces are non-breaking, and so is
/// the one before its "·", which stays at the end of a line rather than starting the next.
private struct Times: View {
    let prep: String?
    let cook: String?
    let total: String?

    var body: some View {
        let entries = [
            prep.map { TimeEntry(label: Strings.labelPrep, value: $0) },
            cook.map { TimeEntry(label: Strings.labelCook, value: $0) },
            total.map { TimeEntry(label: Strings.labelTotal, value: $0) }
        ].compactMap { $0 }
        if !entries.isEmpty {
            Text(Self.line(entries))
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.muted)
                .accessibilityIdentifier("recipe.times")
        }
    }

    private static let nbsp = "\u{00A0}"

    private static func line(_ entries: [TimeEntry]) -> AttributedString {
        var out = AttributedString()
        for (i, entry) in entries.enumerated() {
            if i > 0 { out += AttributedString(nbsp + "· ") }
            out += AttributedString(glued(entry.label) + nbsp)
            var value = AttributedString(glued(entry.value))
            value.inlinePresentationIntent = .stronglyEmphasized
            value.foregroundColor = Palette.onBackground
            out += value
        }
        return out
    }

    /// "1h 15m" kept whole. A long phrase as written ("Overnight, plus 2 hours to rest") keeps
    /// its spaces: unbroken, it would be cut mid-word once it outgrew the line.
    private static func glued(_ text: String) -> String {
        text.count <= 20 ? text.replacingOccurrences(of: " ", with: nbsp) : text
    }
}

private struct TimeEntry {
    let label: String
    let value: String
}

/// Chef mode (#100): a step with a short version toggles to the step as written on a tap, and
/// back. Without one the row is plain text, as before.
private struct ShortStepToggle: ViewModifier {
    let enabled: Bool
    let asWritten: Bool
    let toggle: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .onTapGesture(perform: toggle)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint(asWritten ? Strings.stepShowShort : Strings.stepShowAsWritten)
        } else {
            content
        }
    }
}

/// A step's text with the amounts inserted from its ingredient lines (#101) set apart, in
/// `accent` (nil keeps the text's colour) and a heavier weight, so an insertion never reads as
/// the site's own words. `parts` nil: the step as written. With `linked`, a mention naming
/// several lines with different amounts (#254) is a link in `accent`, which `StepLinks` opens;
/// without (a done step) it reads as plain text.
func stepText(_ text: String, _ parts: [StepAmounts.Part]?, accent: Color?, linked: Bool = false) -> Text {
    guard let parts else { return Text(text) }
    var out = AttributedString()
    for part in parts {
        var run = AttributedString(part.text)
        if part.amount {
            run.inlinePresentationIntent = .stronglyEmphasized
            if let accent { run.foregroundColor = accent }
        } else if linked, !part.lines.isEmpty, let url = StepLinks.url(part.lines) {
            run.link = url
            run.underlineStyle = .single
            if let accent { run.foregroundColor = accent }
        }
        out += run
    }
    return Text(out)
}

/// A step's ingredient links (#254): a tap on one calls `onLink` with its lines, and VoiceOver
/// offers each as an action that reads as a button, "butter, 2 matching ingredients, show".
struct StepLinks: ViewModifier {
    let parts: [StepAmounts.Part]?
    let onLink: (([Int]) -> Void)?

    private static let scheme = "recipeclipper-lines"

    static func url(_ lines: [Int]) -> URL? {
        URL(string: "\(scheme):\(lines.map(String.init).joined(separator: ","))")
    }

    func body(content: Content) -> some View {
        let links = (parts ?? []).filter { !$0.lines.isEmpty }
        if let onLink, !links.isEmpty {
            content
                .environment(\.openURL, OpenURLAction { url in
                    guard url.scheme == Self.scheme else { return .systemAction }
                    onLink(url.absoluteString.dropFirst(Self.scheme.count + 1).split(separator: ",").compactMap { Int($0) })
                    return .handled
                })
                .accessibilityActions {
                    ForEach(Array(links.enumerated()), id: \.offset) { _, link in
                        Button(Strings.stepLinkLabel(link.text, matches: link.lines.count)) { onLink(link.lines) }
                    }
                }
        } else {
            content
        }
    }
}

/// "Back to step N" (#254), after a step's ingredient link: the way back to the step.
struct BackToStepChip: View {
    let step: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(Strings.backToStep(step + 1))
                .textStyle(Typography.labelLarge)
                .foregroundStyle(Palette.accentText)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .frame(minHeight: 44)
                .background(Capsule().fill(Palette.background))
                .overlay(Capsule().stroke(Palette.hairline, lineWidth: 1))
                .shadow(color: .black.opacity(0.08), radius: 3, y: 1)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("recipe.backToStep")
    }
}
