import SwiftUI
import UIKit

/// Whether the `noPhotoImage` flag (#403) is on, set once at the root so every image slot reads it
/// without the flags being passed down. Off (the default, and what a screen test sees), every slot
/// is the blank tile or band it was.
private struct NoPhotoImageKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var noPhotoImage: Bool {
        get { self[NoPhotoImageKey.self] }
        set { self[NoPhotoImageKey.self] = newValue }
    }
}

/// How a recipe with no photo is drawn (#403). `auto` picks by the size it is given.
enum NoPhotoStyle { case auto, monogram, label }

/// The pure choice behind `RecipeImagePlaceholder`: lettering up to this size (the shorter side),
/// the ingredient label above it, the label set low and left in a band or a banner.
enum NoPhotoLayout: Equatable {
    case monogram
    /// A square: the first name, centred.
    case label
    /// A band or a banner: up to three names, set low and left; `tall` is the banner's larger type.
    case band(tall: Bool)

    /// Up to this size (the shorter side) an image is lettering: the title's first letter.
    static let monogramMaxSize: CGFloat = 64
    /// A placeholder this much wider than tall is a band or a banner.
    static let bandRatio: CGFloat = 1.5

    static func of(_ size: CGSize, style: NoPhotoStyle) -> NoPhotoLayout {
        let small = min(size.width, size.height) <= monogramMaxSize
        let wantsLabel = switch style {
        case .monogram: false
        case .label: true
        case .auto: !small
        }
        guard wantsLabel else { return .monogram }
        return size.width >= size.height * bandRatio ? .band(tall: size.height >= 140) : .label
    }

    /// How many ingredient names this layout can show.
    var names: Int {
        switch self {
        case .monogram: 0
        case .label: 1
        case .band: 3
        }
    }
}

/// The designed image of a recipe with no photo (#403): never a stock image, only the recipe's
/// own words on one of five tints chosen by its uid, in Bricolage (#455). A small surface (64 pt and
/// below, by the size it is drawn at) gets the title's first letter; a larger one the recipe's
/// first ingredient names, upright (the first only in a square cover or cell, up to three in a band or a banner),
/// or the letter when it has no ingredient lines. A title with no letter and no names is a plain
/// tint tile.
///
/// Decorative: hidden from VoiceOver, so what a screen announces is what it announced before.
/// With the flag off it is the hairline tile. The caller sizes and clips it.
struct RecipeImagePlaceholder: View {
    let recipe: NoPhotoSource
    var style: NoPhotoStyle = .auto
    var maxLabelLines = 2
    @Environment(\.noPhotoImage) private var on

    var body: some View {
        if on {
            GeometryReader { proxy in
                let layout = NoPhotoLayout.of(proxy.size, style: style)
                let art = NoPhoto.art(recipe, maxLabels: layout.names)
                let tint = Palette.noPhotoTint(art.tint)
                ZStack(alignment: alignment(layout)) {
                    tint.ground
                    if !art.labels.isEmpty {
                        label(art.labels, ink: tint.ink, size: proxy.size, layout: layout)
                    } else if let letter = art.initial {
                        Text(letter)
                            .font(Font(AppFont.bricolage(min(proxy.size.width, proxy.size.height) * 0.56, weight: 700)))
                            .foregroundStyle(tint.ink)
                            .lineLimit(1)
                    }
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
                .accessibilityIdentifier(identifier(art))
            }
            .accessibilityHidden(true)
        } else {
            Palette.hairline
        }
    }

    private func alignment(_ layout: NoPhotoLayout) -> Alignment {
        if case .band = layout { .bottomLeading } else { .center }
    }

    private func identifier(_ art: NoPhotoArt) -> String {
        if !art.labels.isEmpty { "noPhoto.label" } else if art.initial != nil { "noPhoto.monogram" } else { "noPhoto.plain" }
    }

    // Fixed in points, not scaled with the text size: it is decoration, and follows the image.
    private func label(_ names: [String], ink: Color, size: CGSize, layout: NoPhotoLayout) -> some View {
        let band: Bool
        let points: CGFloat
        switch layout {
        case .band(let tall):
            band = true
            points = tall ? 25 : 21
        default:
            band = false
            points = max(10, min(size.width, size.height) * 0.18)
        }
        let font = Font(AppFont.bricolage(points, weight: 600))
        var text = Text(names[0]).font(font)
        for name in names.dropFirst() {
            text = text + Text("  ·  ").font(font).foregroundStyle(ink.opacity(0.5)) + Text(name).font(font)
        }
        return text
            .tracking(points * -0.01)
            .foregroundStyle(ink)
            .multilineTextAlignment(band ? .leading : .center)
            .lineLimit(maxLabelLines)
            .lineSpacing(points * 0.06)
            .padding(.horizontal, band ? 18 : 4)
            .padding(.vertical, band ? 14 : 4)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: band ? .bottomLeading : .center)
    }
}

/// A recipe's picture: `url` (a web image) when it has one, else, and when it fails to load, the
/// recipe's `RecipeImagePlaceholder` (#403). With the flag off a failed load is blank as before.
/// The photo is decorative here; the screen labels it where it did. Fills the room it is given.
struct RecipeImage: View {
    let url: String?
    let recipe: NoPhotoSource
    var style: NoPhotoStyle = .auto
    var maxLabelLines = 2
    @Environment(\.noPhotoImage) private var on
    @State private var failed = false

    var body: some View {
        ZStack {
            Palette.hairline
            if let url, let parsed = URL(string: url) {
                CachedAsyncImage(url: parsed) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Color.clear
                }
                .onFailure { failed = true }
                if failed && on {
                    RecipeImagePlaceholder(recipe: recipe, style: style, maxLabelLines: maxLabelLines)
                }
            } else if on {
                RecipeImagePlaceholder(recipe: recipe, style: style, maxLabelLines: maxLabelLines)
            }
        }
        .onChange(of: url) { failed = false }
    }
}
