import SwiftUI

private let coverRadius: CGFloat = 12
private let mosaicGap: CGFloat = 2

/// One list on the Lists screen (#286): its cover, then its name and count under it. One
/// button, so VoiceOver (and the UI tests) read "Weeknights, 2 recipes".
struct ListCoverTile: View {
    let list: RecipeList
    let photos: [CoverPhoto]
    /// The list's recipes with no photo, for a list with no photo at all (#403).
    var placeholders: [NoPhotoSource] = []
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                CoverMosaic(photos: photos, placeholders: placeholders)
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
/// A list with recipes but no photo shows those recipes' designed images the same way (#403; a
/// list with any photo shows photos alone). An empty list gets a plain tile with an outlined
/// bookmark: nothing saved here yet. With `noPhotoImage` off, no photo at all is that tile too.
private struct CoverMosaic: View {
    let photos: [CoverPhoto]
    let placeholders: [NoPhotoSource]
    @Environment(\.noPhotoImage) private var noPhotoImage

    var body: some View {
        // A square the width of the column; the gaps show the screen's ground.
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { cells }
            .clipShape(RoundedRectangle(cornerRadius: coverRadius))
            .accessibilityHidden(true)
    }

    @ViewBuilder private var cells: some View {
        if photos.isEmpty && noPhotoImage && !placeholders.isEmpty {
            Cells(items: Array(placeholders.prefix(4))) { source in
                RecipeImagePlaceholder(recipe: source).accessibilityIdentifier("lists.coverPlaceholder")
            }
        } else if photos.isEmpty {
            ZStack {
                Palette.hairline
                Image(systemName: "bookmark")
                    .font(.system(size: 24))
                    .foregroundStyle(Palette.muted)
            }
        } else {
            Cells(items: photos) { CoverPhotoCell(photo: $0) }
        }
    }
}

/// One to four cells in the cover's layout, each drawn by `cell`.
private struct Cells<Item, Cell: View>: View {
    let items: [Item]
    @ViewBuilder let cell: (Item) -> Cell

    var body: some View {
        switch items.count {
        case 1:
            cell(items[0])
        case 2:
            HStack(spacing: mosaicGap) { cell(items[0]); cell(items[1]) }
        case 3:
            HStack(spacing: mosaicGap) {
                cell(items[0])
                VStack(spacing: mosaicGap) { cell(items[1]); cell(items[2]) }
            }
        default:
            VStack(spacing: mosaicGap) {
                HStack(spacing: mosaicGap) { cell(items[0]); cell(items[1]) }
                HStack(spacing: mosaicGap) { cell(items[2]); cell(items[3]) }
            }
        }
    }
}

/// One cell, cropped to fill; the hairline tint shows until it loads. `CachedAsyncImage`
/// goes through ImageLoader's cache, so a cover shows offline too. A photo that fails to load shows
/// its recipe's designed image instead of the blank tile (#403).
private struct CoverPhotoCell: View {
    let photo: CoverPhoto

    var body: some View {
        RecipeImage(url: photo.url, recipe: photo.source)
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
