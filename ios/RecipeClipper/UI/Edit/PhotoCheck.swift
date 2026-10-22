import SwiftUI

/// The pictures read, as a row of thumbnails over the cards (#286), with "2 pages · 5 lines to
/// check". Tapping one shows it large under the row, and tapping it again hides it, to check the
/// lines that aren't listed against it. A scan's pages are read from their files, never cached.
/// Android's `PageThumbnails`.
struct PageThumbnails: View {
    let post: PhotoPost
    let state: EditRecipeUiState
    let onPage: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    thumbnails
                    count
                }
                VStack(alignment: .leading, spacing: 8) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { thumbnails }
                    }
                    count
                }
            }
            if let page = state.openPage, post.imageUrls.indices.contains(page), let url = URL(string: post.imageUrls[page]) {
                CachedAsyncImage(url: url) { picture in
                    picture.resizable().scaledToFit()
                } placeholder: {
                    Rectangle().fill(Palette.hairline).frame(height: 200)
                }
                .frame(maxWidth: .infinity, maxHeight: 480)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel(description(page))
            }
        }
    }

    private var thumbnails: some View {
        ForEach(Array(post.imageUrls.enumerated()), id: \.offset) { index, image in
            let open = state.openPage == index
            Button { onPage(index) } label: {
                ReviewImage(url: image, maxSide: 216) { picture in
                    if let picture {
                        Image(uiImage: picture).resizable().scaledToFill()
                    } else {
                        Palette.hairline
                    }
                }
                .frame(width: 56, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(open ? Palette.primary : .clear, lineWidth: 2))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(description(index))
            .accessibilityAddTraits(open ? .isSelected : [])
        }
    }

    private var count: some View {
        let pages = state.scan ? Strings.photoPagesCount(state.pageCount) : Strings.photoPhotosCount(state.pageCount)
        let lines = state.checks.count
        return Text(lines == 0 ? pages : "\(pages) · \(Strings.photoLinesToCheck(lines))")
            .textStyle(Typography.bodyMedium)
            .foregroundStyle(Palette.muted)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("edit.pageCount")
    }

    private func description(_ index: Int) -> String {
        state.scan ? Strings.scanPageDescription(index + 1, post.imageUrls.count) : Strings.photoImageDescription(post.title)
    }
}
