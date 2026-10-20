import SwiftUI

/// Continue cooking (#286): the most recent recipe's photo, or a plain themed band when it has
/// none, its name, and only what is true of it now: the step stopped on and a running timer while
/// it is being cooked (`resume`), then "Resume cooking" into cook mode, or else "Open recipe".
/// The photo and name open the recipe, as the Continue cooking row did.
struct ResumeCard: View {
    let recipe: RecipeSummary
    let resume: CookResume?
    let onResume: () -> Void
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 0) {
                    photo
                    VStack(alignment: .leading, spacing: 2) {
                        // Read as the button's hint instead: its label is the recipe's name.
                        SmallCapsLabel(Strings.sectionContinueCooking).accessibilityHidden(true)
                        Text(recipe.title)
                            .textStyle(Typography.titleLarge)
                            .foregroundStyle(Palette.onBackground)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                    }
                    .padding([.horizontal, .top], 16)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Strings.sectionContinueCooking)
            VStack(alignment: .leading, spacing: 8) {
                if let resume {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { place(resume) }
                        VStack(alignment: .leading, spacing: 4) { place(resume) }
                    }
                }
                Button(resume != nil ? Strings.homeResumeCooking : Strings.homeOpenRecipe) {
                    if resume != nil { onResume() } else { onOpen() }
                }
                .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                .accessibilityIdentifier("home.resume")
            }
            .padding([.horizontal, .bottom], 16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    /// The recipe's photo, or a plain themed band: never a stock image.
    @ViewBuilder private var photo: some View {
        if let image = recipe.imageUrl, let url = URL(string: image) {
            Color.clear
                .frame(maxWidth: .infinity)
                .frame(height: 136)
                .overlay {
                    CachedAsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Palette.hairline }
                }
                .clipped()
                .accessibilityHidden(true)
        } else {
            Palette.accentText.opacity(0.12).frame(height: 56).accessibilityHidden(true)
        }
    }

    /// A running timer's time left, on the paprika wash, then the step.
    @ViewBuilder private func place(_ resume: CookResume) -> some View {
        if let seconds = resume.timerSecondsLeft {
            Label(Strings.homeTimerLeft(StepTimers.clock(seconds)), systemImage: "timer")
                .textStyle(Typography.labelMedium)
                .foregroundStyle(Palette.accentText)
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(Palette.accentText.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
        }
        Text(Strings.cookPosition(resume.step, of: resume.stepCount))
            .textStyle(Typography.bodySmall)
            .foregroundStyle(Palette.muted)
    }
}

/// Small spaced capitals over a block (#286), read by VoiceOver in sentence case as a header.
struct SmallCapsLabel: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased())
            .textStyle(Typography.labelSmall)
            .foregroundStyle(Palette.muted)
            .accessibilityLabel(text)
            .accessibilityAddTraits(.isHeader)
    }
}
