import SwiftUI
import UIKit

/// An AI's answer pasted in that stopped before its recipe ended (#352; Android's `CutOffScreen`):
/// `ParseError.cutOff` in the screen's words, and "Copy a message for the AI" to ask the chat to
/// send it again. Nothing is saved or kept; Back returns to where the paste came from.
struct CutOffScreen: View {
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(Strings.message(for: .cutOff))
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.error)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("cutoff.message")
                if copied {
                    Text(Strings.cutOffCopied)
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.muted)
                        .accessibilityIdentifier("cutoff.copied")
                } else {
                    Button(Strings.cutOffCopyAction) {
                        UIPasteboard.general.string = CutOffMessage.forRecipe(Strings.cutOffWords)
                        copied = true
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("cutoff.copy")
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 24)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
    }
}
