import SwiftUI

/// The Pro screen (#413; Android's `ProScreen`, the same page): every locked Week extra opens it, and it
/// is Settings' Pro page. What Pro adds, "one-time purchase, never a subscription", the unlock (with the
/// store's price once the store gives it, never a figure of ours), Restore purchases, and "Free. No ads.
/// No account. No subscription." Unlocked, the button gives way to a thank-you line.
struct ProScreen: View {
    let vm: ProViewModel

    private static let features: [(String, String)] = [
        (Strings.proFeatureFreshTitle, Strings.proFeatureFreshBody),
        (Strings.proFeatureRemindersTitle, Strings.proFeatureRemindersBody),
        (Strings.proFeatureSkipTitle, Strings.proFeatureSkipBody),
        (Strings.proFeaturePlanTitle, Strings.proFeaturePlanBody)
    ]

    var body: some View {
        let pro = vm.pro
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ProBadge().padding(.top, 12)
                ScreenTitle(Strings.proTitle)
                    .padding(.top, 8)
                Text(Strings.proIntro)
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 6)
                Hairline().padding(.top, 16)
                ForEach(Self.features, id: \.0) { title, body in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).textStyle(Typography.bodyLargeBold).foregroundStyle(Palette.onBackground)
                        Text(body).textStyle(Typography.bodyMedium).foregroundStyle(Palette.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 16)
                }
                Hairline().padding(.top, 20)
                Text(Strings.proOneTime)
                    .textStyle(Typography.bodyLargeBold)
                    .foregroundStyle(Palette.onBackground)
                    .padding(.top, 20)
                if pro.isPro {
                    Text(Strings.proUnlocked)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.onBackground)
                        .padding(.top, 12)
                        .accessibilityIdentifier("pro.unlocked")
                } else {
                    Button(pro.price.map(Strings.proUnlockPrice) ?? Strings.proUnlock) { vm.onUnlock() }
                        .buttonStyle(PrimaryButtonStyle(fillWidth: true))
                        .disabled(state.busy)
                        .padding(.top, 12)
                        .accessibilityIdentifier("pro.unlock")
                    Button(Strings.proRestore) { vm.onRestore() }
                        .buttonStyle(TextActionStyle())
                        .disabled(state.busy)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("pro.restore")
                    if let status = pro.pending ? Strings.unlimitedPending : state.notice.map(Strings.unlockNotice) {
                        Text(status)
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.onBackground)
                            .padding(.top, 4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("pro.notice")
                    }
                }
                Text(Strings.proFreeLine)
                    .textStyle(Typography.bodyMedium)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 24)
                    .accessibilityIdentifier("pro.freeLine")
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("pro.screen")
    }
}
