import SwiftUI

/// The reading view opens on the recipe: photo, title, times, one servings-and-units row,
/// ingredients. Cooking is one bottom button.
struct ReadingView: View {
    let content: RecipeSuccess
    let state: RecipeUiState
    let vm: RecipeViewModel
    /// "Your cooks" (#116); nil while its flag is off.
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
                    IngredientRow(
                        text: ingredient,
                        checked: state.checkedIngredients.contains(index),
                        onCheckedChange: { vm.onIngredientChecked(index, $0) }
                    )
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
                        stepText(step, content.shownStepParts(index, asWritten: state.asWrittenSteps), accent: Palette.accentText)
                            .textStyle(Typography.bodyLarge)
                            .foregroundStyle(Palette.onBackground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
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
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !content.instructions.isEmpty && !editingNotes {
                VStack(spacing: 0) {
                    Hairline()
                    Button(Strings.startCooking, action: vm.onCookStart)
                        .buttonStyle(PrimaryButtonStyle(minHeight: 52, fillWidth: true))
                        .padding(.horizontal, 20)
                        .readableColumn()
                        .padding(.vertical, 12)
                }
                .background(Palette.background)
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

/// Plain labeled numbers. Deliberately not chips: chips imply tappable, these aren't.
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
            // In a row while the three fit; stacked once they don't (the accessibility sizes,
            // or a long "1 hr 15 mins"), rather than wrapping each value mid-number.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 28) {
                    ForEach(entries, id: \.label) { TimeCell(entry: $0).fixedSize() }
                }
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(entries, id: \.label) { TimeCell(entry: $0) }
                }
            }
        }
    }
}

private struct TimeCell: View {
    let entry: TimeEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(entry.label.uppercased())
                .textStyle(Typography.labelSmall)
                .foregroundStyle(Palette.muted)
            Text(entry.value)
                .textStyle(Typography.bodyLargeBold)
                .foregroundStyle(Palette.onBackground)
        }
        .accessibilityElement(children: .combine)
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
/// the site's own words. `parts` nil: the step as written.
func stepText(_ text: String, _ parts: [StepAmounts.Part]?, accent: Color?) -> Text {
    guard let parts else { return Text(text) }
    var out = AttributedString()
    for part in parts {
        var run = AttributedString(part.text)
        if part.amount {
            run.inlinePresentationIntent = .stronglyEmphasized
            if let accent { run.foregroundColor = accent }
        }
        out += run
    }
    return Text(out)
}
