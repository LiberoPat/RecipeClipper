import ImageIO
import PhotosUI
import SwiftUI

/// "Your cooks" (#116; Android's CookedPhotosSection): at the foot of the reading view, after
/// the steps and the note, so the recipe still opens on the recipe. Empty, one quiet line and
/// "I made this"; with photos, a row of thumbnails (newest cook first, each dated) and a +.
struct CookedPhotosSection: View {
    let vm: CookedPhotosViewModel
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var pickingLibrary = false
    @State private var takingPhoto = false
    @State private var noCamera = false

    var body: some View {
        let photos = vm.uiState.photos
        VStack(alignment: .leading, spacing: 0) {
            SectionHeading(Strings.headingYourCooks).padding(.bottom, 8)
            if photos.isEmpty {
                Text(Strings.cookedEmpty)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.bottom, 10)
                addMenu {
                    Text(Strings.iMadeThis)
                }
                .buttonStyle(OutlinedActionStyle())
                .accessibilityIdentifier("cooked.iMadeThis")
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 10) {
                        ForEach(photos) { photo in
                            Button { vm.onOpen(photo) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    LocalPhoto(path: photo.path, maxPixels: 300, fill: true)
                                        .frame(width: 96, height: 96)
                                        .clipShape(RoundedRectangle(cornerRadius: 10))
                                    Text(PlanDayFormat.shortDate(photo.day))
                                        .textStyle(Typography.labelMedium)
                                        .foregroundStyle(Palette.muted)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(Strings.cookedPhoto(PlanDayFormat.fullDate(photo.day)))
                            .accessibilityAddTraits(.isButton)
                        }
                        addMenu {
                            Image(systemName: "plus")
                                .frame(width: 96, height: 96)
                                .background(RoundedRectangle(cornerRadius: 10).fill(Palette.surfaceContainer))
                        }
                        .accessibilityLabel(Strings.addPhoto)
                    }
                }
            }
        }
        .photosPicker(isPresented: $pickingLibrary, selection: $pickerItems, maxSelectionCount: 10, matching: .images)
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            pickerItems = []
            // Loading the picked items is the picker's own platform work; the data goes to the VM.
            Task {
                var pictures: [Data] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self) { pictures.append(data) }
                }
                vm.onAdd(pictures)
            }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker { data in vm.onAdd([data]) }.ignoresSafeArea()
        }
        .alert(Strings.cameraUnavailable, isPresented: $noCamera) {}
        .alert(Strings.cookedAddFailed, isPresented: Binding(
            get: { vm.uiState.addFailed }, set: { if !$0 { vm.onAddFailedShown() } }
        )) {}
    }

    /// Camera or library, from whatever `label` draws.
    private func addMenu<Label: View>(@ViewBuilder label: () -> Label) -> some View {
        Menu {
            Button(Strings.takePhoto) {
                if UIImagePickerController.isSourceTypeAvailable(.camera) { takingPhoto = true } else { noCamera = true }
            }
            Button(Strings.choosePhotos) { pickingLibrary = true }
        } label: {
            label()
        }
    }
}

/// A stored photo, decoded off the main thread at no more than `maxPixels` on its long edge; one
/// whose file isn't here (a restore without photos) says so instead.
struct LocalPhoto: View {
    let path: String
    let maxPixels: Int
    var fill = false
    @State private var image: UIImage?
    @State private var missing = false

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: fill ? .fill : .fit)
            } else if missing {
                Text(Strings.cookedPhotoMissing)
                    .textStyle(Typography.labelSmall)
                    .foregroundStyle(Palette.muted)
                    .padding(6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Palette.surfaceContainer)
            }
        }
        .task(id: path) {
            let (path, max) = (path, maxPixels)
            let decoded = await Task.detached { LocalPhoto.decode(path, max) }.value
            image = decoded.map(UIImage.init(cgImage:))
            missing = decoded == nil
        }
    }

    nonisolated static func decode(_ path: String, _ max: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// The system camera (UIKit), handing back the picture as JPEG data; the store turns it
/// upright and downscales it. Asks for camera access the first time only, when chosen.
struct CameraPicker: UIViewControllerRepresentable {
    let onPicture: (Data) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let data = (info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.95) { parent.onPicture(data) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
