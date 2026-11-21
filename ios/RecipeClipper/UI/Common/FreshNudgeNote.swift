import SwiftUI

/// The plan's quiet note about a perishable meal planned later than its group keeps (#396; Android's
/// `FreshNudgeNote`): "Salmon fillets: keeps about 1-2 days once bought. Cook it earlier?", with the one
/// swap that fixes it. It says only what the table says (a range, never a date) and moves nothing
/// until the swap is tapped. `locked` (#413, Pro): the swap carries the Pro badge and the caller's
/// `onSwap` opens the Pro screen.
struct FreshNudgeNote: View {
    let nudge: FreshNudge
    var locked = false
    let onSwap: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(Strings.freshNudge(nudge.name, nudge.keeps))
                .textStyle(Typography.bodySmall)
                .foregroundStyle(Palette.muted)
            ProTextButton(label: Strings.freshNudgeSwap(PlanDayFormat.title(nudge.swapDay)), locked: locked, action: onSwap)
                .padding(.leading, -12)
                .accessibilityIdentifier("freshSwap")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
