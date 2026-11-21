import SwiftUI

/// The badge's type: Karla 11pt semibold, a little tracked, as Android's (labelSmall at 11sp, SemiBold).
private let badgeStyle = TextStyle(family: .karla, baseSize: 11, weight: 600, baseLineHeight: 14, tracking: 0.4, relativeTo: .caption2)

/// The quiet "Pro" mark on a Week planning extra's entry point (#413; Android's `ProBadge`): paprika
/// text in a thin paprika outline, no fill. The extra stays visible; a tap on it opens the Pro screen.
struct ProBadge: View {
    var body: some View {
        Text(Strings.proBadge)
            .textStyle(badgeStyle)
            .foregroundStyle(Palette.accentText)
            .padding(.horizontal, 7)
            .padding(.vertical, 1)
            .overlay(Capsule().stroke(Palette.accentText.opacity(0.5), lineWidth: 1))
            .fixedSize()
            .accessibilityIdentifier("proBadge")
    }
}

/// A text button's label with the `ProBadge` after it: a locked extra's own button (#413; Android's
/// `ProTextButton`).
struct ProTextButton: View {
    let label: String
    let locked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(label)
                if locked { ProBadge() }
            }
        }
        .buttonStyle(TextActionStyle())
    }
}

/// A locked extra that has no button of its own to carry the badge (#413; Android's `ProTeaserRow`):
/// one quiet line saying what it would do, the badge after it, and a tap opens the Pro screen.
struct ProTeaserRow: View {
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(text)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .multilineTextAlignment(.leading)
                ProBadge()
                Spacer(minLength: 0)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("proTeaser")
    }
}
