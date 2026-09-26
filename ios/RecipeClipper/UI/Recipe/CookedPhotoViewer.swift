import SwiftUI

/// One photo, full screen (#116; Android's CookedPhotoViewer): the picture, its date (tap for a
/// date picker) and its note, edited in place; Share (the photo and the recipe's name as plain
/// text) and Delete, which the recipe screen offers to undo.
struct CookedPhotoViewer: View {
    let vm: CookedPhotosViewModel
    let photo: CookedPhoto
    let recipeName: String

    var body: some View {
        let date = PlanDayFormat.fullDate(photo.day)
        VStack(spacing: 0) {
            HStack {
                Button { vm.onClose() } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(Strings.closePhoto)
                Spacer()
                if photo.hasPicture {
                    ShareLink(item: URL(fileURLWithPath: photo.path), message: Text(recipeName)) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .accessibilityLabel(Strings.sharePhoto)
                }
                Button { vm.onDelete() } label: { Image(systemName: "trash") }
                    .accessibilityLabel(Strings.deletePhoto)
                    .accessibilityIdentifier("cooked.delete")
                    .padding(.leading, 20)
            }
            .font(.title3)
            .tint(Palette.onBackground)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)

            LocalPhoto(path: photo.path, maxPixels: photoMaxEdge)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 8) {
                // Midnight UTC, like every day label (PlanDayFormat), so the day never slips.
                HStack(spacing: 8) {
                    Image(systemName: "calendar").foregroundStyle(Palette.accentText)
                    DatePicker(
                        Strings.changeCookedDate(date),
                        selection: Binding(get: { PlanDays.utcDate(photo.day) }, set: { vm.onDayChange(PantryDate.day($0)) }),
                        displayedComponents: .date
                    )
                    .labelsHidden()
                    .environment(\.timeZone, TimeZone(identifier: "UTC")!)
                    .tint(Palette.primary)
                }
                TextField(
                    Strings.cookedNotePlaceholder,
                    text: Binding(get: { vm.uiState.noteDraft }, set: vm.onNoteChange),
                    prompt: Text(Strings.cookedNotePlaceholder).foregroundStyle(Palette.muted),
                    axis: .vertical
                )
                .lineLimit(1...3)
                .textInputAutocapitalization(.sentences)
                .textStyle(Typography.bodyLarge)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.outline, lineWidth: 1))
                .accessibilityIdentifier("cooked.note")
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .readableColumn()
        }
        .screenBackground()
    }
}
