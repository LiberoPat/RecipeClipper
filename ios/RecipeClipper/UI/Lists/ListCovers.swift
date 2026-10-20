import SwiftUI

private let coverRadius: CGFloat = 12
private let mosaicGap: CGFloat = 2

/// One list on the Lists screen (#286): its cover, then its name and count under it. One
/// button, so VoiceOver (and the UI tests) read "Weeknights, 2 recipes".
struct ListCoverTile: View {
    let list: RecipeList
    let photos: [String]
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                CoverMosaic(photos: photos)
                    .padding(.bottom, 8)
                // No line limit: at the large text sizes a name wraps rather than clipping.
                Text(list.name)
                    .textStyle(Typography.titleSmall)
                    .foregroundStyle(Palette.onBackground)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Text(Strings.listCount(list.recipeCount))
                    .textStyle(Typography.bodySmall)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("lists.cover.\(list.id)")
    }
}

/// Four photos make a 2×2. Fewer fill the square without empty cells: one fills it, two sit
/// side by side, three put the newest on the left half with the next two stacked on the right.
/// No photo at all gets a plain tile with an outlined bookmark: a list, not a missing image.
private struct CoverMosaic: View {
    let photos: [String]

    var body: some View {
        // A square the width of the column; the gaps show the screen's ground.
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { cells }
            .clipShape(RoundedRectangle(cornerRadius: coverRadius))
            .accessibilityHidden(true)
    }

    @ViewBuilder private var cells: some View {
        switch photos.count {
        case 0:
            ZStack {
                Palette.hairline
                Image(systemName: "bookmark")
                    .font(.system(size: 24))
                    .foregroundStyle(Palette.muted)
            }
        case 1:
            CoverPhoto(url: photos[0])
        case 2:
            HStack(spacing: mosaicGap) { CoverPhoto(url: photos[0]); CoverPhoto(url: photos[1]) }
        case 3:
            HStack(spacing: mosaicGap) {
                CoverPhoto(url: photos[0])
                VStack(spacing: mosaicGap) { CoverPhoto(url: photos[1]); CoverPhoto(url: photos[2]) }
            }
        default:
            VStack(spacing: mosaicGap) {
                HStack(spacing: mosaicGap) { CoverPhoto(url: photos[0]); CoverPhoto(url: photos[1]) }
                HStack(spacing: mosaicGap) { CoverPhoto(url: photos[2]); CoverPhoto(url: photos[3]) }
            }
        }
    }
}

/// One cell, cropped to fill; the hairline tint shows until it loads. `CachedAsyncImage`
/// goes through ImageLoader's cache, so a cover shows offline too.
private struct CoverPhoto: View {
    let url: String

    var body: some View {
        Palette.hairline
            .overlay {
                if let parsed = URL(string: url) {
                    CachedAsyncImage(url: parsed) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Color.clear
                    }
                }
            }
            .clipped()
    }
}

/// The last tile: a dashed square with a plus and "New list" under it. It opens the same
/// inline field "+ New list" always has.
struct NewListTile: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                RoundedRectangle(cornerRadius: coverRadius)
                    .strokeBorder(Palette.outline, style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        Image(systemName: "plus")
                            .font(.system(size: 28))
                            .foregroundStyle(Palette.accentText)
                    }
                    .accessibilityHidden(true)
                    .padding(.bottom, 8)
                Text(Strings.newListTile)
                    .textStyle(Typography.titleSmall)
                    .foregroundStyle(Palette.accentText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("lists.newList")
    }
}
