import SwiftUI

/// Whether the `stepPhotos` flag (#433) is on, set once at the root. Off (the default, and what a
/// screen test sees unless it sets it), no step shows a photo: the screens are as before.
private struct StepPhotosKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var stepPhotos: Bool {
        get { self[StepPhotosKey.self] }
        set { self[StepPhotosKey.self] = newValue }
    }
}

/// What a step's thumbnail is (#433): nothing for a step without photos, one photo with its
/// badge, or a stack of the first two with a "+N" pill for two or more. Pure, so the choice and
/// what VoiceOver reads are tested without drawing.
enum StepPhotoThumb: Equatable {
    case none
    case single(StepPhoto)
    case stack(front: StepPhoto, behind: StepPhoto, more: Int)

    static func of(_ photos: [StepPhoto]) -> StepPhotoThumb {
        guard let first = photos.first else { return .none }
        guard photos.count > 1 else { return .single(first) }
        return .stack(front: first, behind: photos[1], more: photos.count - 1)
    }

    var front: StepPhoto? {
        switch self {
        case .none: return nil
        case .single(let photo), .stack(let photo, _, _): return photo
        }
    }

    /// "Photo 3 of step 2", or "2 photos of step 3" for a stack.
    var accessibilityLabel: String {
        switch self {
        case .none: return ""
        case .single(let photo): return Strings.stepPhotoOpen(photo.number, step: photo.step + 1)
        case .stack(let front, _, let more): return Strings.stepPhotosOpen(more + 1, step: front.step + 1)
        }
    }
}

/// The reading view's (54 pt) and cook mode's current card's (56 pt) thumbnail of a step's photos:
/// the first, rounded, with its number on a small badge; two or more stack the second behind it
/// with "+N". A tap opens the viewer on the first. A front photo that fails to load hides the
/// whole thumbnail: never a placeholder.
struct StepPhotoThumbnail: View {
    let photos: [StepPhoto]
    var size: CGFloat = 54
    var radius: CGFloat = 9
    let onOpen: (Int) -> Void

    @State private var failed = false

    var body: some View {
        let thumb = StepPhotoThumb.of(photos)
        if let front = thumb.front, !failed {
            Button { onOpen(front.position) } label: {
                ZStack(alignment: .topTrailing) {
                    if case .stack(_, let behind, _) = thumb {
                        StepPhotoImage(url: behind.url, radius: radius)
                            .frame(width: size, height: size)
                            .opacity(0.55)
                            .offset(x: 4, y: -4)
                    }
                    StepPhotoImage(url: front.url, radius: radius, onFailure: { failed = true })
                        .frame(width: size, height: size)
                        .overlay(alignment: .bottomLeading) {
                            StepPhotoBadge(number: front.number).padding(6)
                        }
                    if case .stack(_, _, let more) = thumb {
                        Text(verbatim: "+\(more)")
                            .font(.system(size: 10, weight: .bold))
                            .monospacedDigit()
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(Palette.primary))
                            .offset(x: 4, y: -8)
                    }
                }
                .frame(width: size, height: size)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(thumb.accessibilityLabel)
            .accessibilityHint(Strings.stepPhotoOpenAction)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("stepPhoto.thumb-\(front.step)")
        }
    }
}

/// Cook mode's done and upcoming rows' 40 pt thumbnail: the first photo alone, no badge, no pill.
/// Decorative: the row's tap makes the step current, and VoiceOver reads the row as before.
struct StepPhotoMiniThumbnail: View {
    let photos: [StepPhoto]
    @State private var failed = false

    var body: some View {
        if let front = photos.first, !failed {
            StepPhotoImage(url: front.url, radius: 7, onFailure: { failed = true })
                .frame(width: 40, height: 40)
                .accessibilityHidden(true)
        }
    }
}

/// The number a step photo's "(n)" marker uses, on ink at 78%: a badge, fixed-size like one.
private struct StepPhotoBadge: View {
    let number: Int

    var body: some View {
        Text(verbatim: "\(number)")
            .font(.system(size: 10.5, weight: .bold))
            .monospacedDigit()
            .foregroundStyle(Color(uiColor: Tokens.ground))
            .padding(.horizontal, 5)
            .frame(minWidth: 18, minHeight: 18)
            .background(Capsule().fill(Color(uiColor: Tokens.ink).opacity(0.78)))
    }
}

/// One step photo, cropped to its frame, through `ImageLoader` (cached, so it shows offline once
/// seen). While it loads, a quiet tile; `onFailure` tells the thumbnail to hide.
private struct StepPhotoImage: View {
    let url: String
    let radius: CGFloat
    var onFailure: () -> Void = {}

    var body: some View {
        CachedAsyncImage(url: URL(string: url)) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Palette.surfaceContainer
        }
        .onFailure(onFailure)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// The viewer's dark ground and its words, in both themes (#433).
private enum ViewerColors {
    static let ground = Color(red: 0x0E / 255, green: 0x0C / 255, blue: 0x0B / 255)
    static let text = Color(red: 0xF1 / 255, green: 0xEC / 255, blue: 0xE6 / 255)
    static let stepNumber = Color(uiColor: Tokens.paprikaTextOnInk)
}

/// Every step photo of the recipe, full screen on a dark ground (#433): close, "Photo 3 of 7",
/// "Step 3", the photo, page dots and the step's text under it; swipe through them all in order.
/// The page lives in the ViewModel (`stepPhotoViewer`), so the view only forwards.
struct StepPhotoViewer: View {
    let content: RecipeSuccess
    let position: Int
    let onPage: (Int) -> Void
    let onClose: () -> Void

    var body: some View {
        let photos = content.allStepPhotos
        let current = min(max(position, 0), max(photos.count - 1, 0))
        VStack(spacing: 0) {
            HStack {
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.title3)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(Strings.closePhoto)
                .accessibilityIdentifier("stepPhoto.close")
                Spacer()
                if photos.indices.contains(current) {
                    Text(Strings.stepPhotoPosition(current + 1, of: photos.count))
                        .textStyle(Typography.labelMedium)
                        .opacity(0.75)
                        .accessibilityIdentifier("stepPhoto.position")
                    Spacer()
                    Text(Strings.stepPhotoStep(photos[current].step + 1))
                        .textStyle(Typography.labelMedium)
                        .opacity(0.75)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)

            TabView(selection: Binding(get: { current }, set: onPage)) {
                ForEach(photos, id: \.position) { photo in
                    CachedAsyncImage(url: URL(string: photo.url)) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        Color.clear
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Strings.stepPhotoOpen(photo.number, step: photo.step + 1))
                    .accessibilityAddTraits(.isImage)
                    .tag(photo.position)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .accessibilityIdentifier("stepPhoto.pager")

            if photos.count > 1 {
                HStack(spacing: 5) {
                    ForEach(photos, id: \.position) { photo in
                        Circle()
                            .fill(ViewerColors.text)
                            .opacity(photo.position == current ? 1 : 0.3)
                            .frame(width: 6, height: 6)
                    }
                }
                .padding(.vertical, 14)
                .accessibilityHidden(true)
            }

            if photos.indices.contains(current) {
                let step = photos[current].step
                ScrollView {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(verbatim: "\(step + 1)")
                            .textStyle(Typography.titleMedium)
                            .foregroundStyle(ViewerColors.stepNumber)
                        Text(content.instructions.indices.contains(step) ? content.instructions[step] : "")
                            .textStyle(Typography.bodyMedium)
                            .opacity(0.9)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .accessibilityElement(children: .combine)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 6)
                    .accessibilityIdentifier("stepPhoto.caption")
                }
                .frame(maxHeight: 200)
                .readableColumn()
            }
        }
        .padding(.bottom, 12)
        .foregroundStyle(ViewerColors.text)
        .tint(ViewerColors.text)
        .background(ViewerColors.ground.ignoresSafeArea())
        .environment(\.colorScheme, .dark)
    }
}
