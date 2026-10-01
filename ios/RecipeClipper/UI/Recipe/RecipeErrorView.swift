import SwiftUI

/// A load that failed (Android's `RecipeErrorView`): what went wrong, and what can be done about
/// it. Every error offers "Try again"; a page with no recipe also offers "Clip it yourself" (#37)
/// and "Report this site" (#30), and a Reddit post with a photo "Read the photo" (#198).
struct RecipeErrorView: View {
    let error: ParseError
    let state: RecipeUiState
    let vm: RecipeViewModel
    /// The `photoText` flag (#198): "Read the photo" shows only behind it.
    let photoTextEnabled: Bool
    /// Opens "Clip it yourself" on the shared link (#37).
    let onClip: (String) -> Void
    /// Opens the editor on a Reddit post's photos, read on the device (#198).
    let onReadPhoto: (PhotoPost) -> Void

    @Environment(\.openURL) private var openURL

    var body: some View {
        StatusView {
            if case .noTranscription(let title, let imageUrl, _) = error {
                PostPreview(title: title, imageUrl: imageUrl)
            }
            Text(Strings.message(for: error))
                .textStyle(Typography.bodyLarge)
                // No transcription is an outcome, not a failure: muted, not red.
                .foregroundStyle(error.isNoTranscription ? Palette.muted : Palette.error)
            // Every error offers "Try again", no-recipe included: a café or hotel captive
            // portal serves its login page, which parses as a page with no recipe, and
            // the same link works once you're through it.
            errorActions
                .padding(.top, 16)
        }
    }

    /// "Try again" on every error. For a page with no recipe data (the ViewModel decides) it is
    /// outlined, and under a hairline "Clip it yourself" (#37) is the one filled button, with
    /// "Report this site" (#30) as the quiet option below it.
    @ViewBuilder
    private var errorActions: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let photoPost = state.photoPost, photoTextEnabled {
                // A post with a photo (#198): Try again, and beside it the photo read on the
                // device, for the cook to check. Stacked when the words don't fit side by side.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { readPhotoActions(photoPost) }
                    VStack(alignment: .leading, spacing: 12) { readPhotoActions(photoPost) }
                }
            } else if let clipUrl = state.clipUrl {
                Button(Strings.tryAgain, action: vm.onRetry)
                    .buttonStyle(OutlinedActionStyle())
                Hairline().padding(.top, 20).padding(.bottom, 16)
                Text(Strings.clipOffer)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 12)
                Button(Strings.clipItYourself) { onClip(clipUrl) }
                    .buttonStyle(PrimaryButtonStyle())
            } else {
                Button(Strings.tryAgain, action: vm.onRetry)
                    .buttonStyle(PrimaryButtonStyle())
            }
            if let report = state.reportSiteUrl.flatMap(URL.init(string:)) {
                Button(Strings.reportSite) { openURL(report) }
                    .buttonStyle(TextActionStyle(color: Palette.muted))
                    .padding(.top, 8)
            }
        }
    }

    @ViewBuilder
    private func readPhotoActions(_ post: PhotoPost) -> some View {
        Button(Strings.tryAgain, action: vm.onRetry)
            .buttonStyle(OutlinedActionStyle())
        Button(Strings.readPhoto) { onReadPhoto(post) }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("recipe.readPhoto")
    }
}

/// A Reddit post with no recipe as text: its title and photo above the note, so the user sees
/// what they shared (the recipe may well be legible in the photo itself). Not the ReadingView:
/// there is nothing to scale, tick or cook.
private struct PostPreview: View {
    let title: String
    let imageUrl: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let imageUrl, let url = URL(string: imageUrl) {
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
                    .accessibilityLabel(title)
                    .padding(.bottom, 16)
            }
            Text(title)
                .textStyle(Typography.headlineSmall)
                .foregroundStyle(Palette.onBackground)
                .padding(.bottom, 12)
        }
    }
}

private extension ParseError {
    var isNoTranscription: Bool {
        if case .noTranscription = self { return true }
        return false
    }
}

/// Loading and errors: one message, nothing to read yet.
struct StatusView<Body: View>: View {
    @ViewBuilder let body_: () -> Body
    init(@ViewBuilder _ body: @escaping () -> Body) { body_ = body }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            body_()
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .readableColumn()
        .padding(.top, 24)
    }
}
