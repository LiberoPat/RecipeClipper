import SwiftUI

extension View {
    /// A typed-in or clipped recipe can't be saved (#107): the free library is full and every
    /// recipe is protected. The editor or clip stays open behind it, so nothing typed is lost.
    /// Android's `LibraryFullDialog`.
    func libraryFullAlert(isPresented: Binding<Bool>, onUnlock: @escaping () -> Void) -> some View {
        alert(Strings.libraryFullTitle, isPresented: isPresented) {
            Button(Strings.unlock, action: onUnlock)
            Button(Strings.cancel, role: .cancel) {}
        } message: {
            Text(Strings.libraryFullBody)
        }
    }
}
