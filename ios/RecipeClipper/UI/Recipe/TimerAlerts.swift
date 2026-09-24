import AVFoundation
import SwiftUI

/// Plays the "time's up" beeps for any timer that just ran out, even if the user has left
/// cook mode, then tells the ViewModel. Marking a timer alerted only after the sound keeps
/// the alarm from repeating. A view observing state and firing an effect — it must not move
/// into the ViewModel, which may not touch audio APIs.
private struct TimerAlertsModifier: ViewModifier {
    let timers: [Int: StepTimer]
    let onAlerted: (Int) -> Void

    func body(content: Content) -> some View {
        content.task(id: TimerAlarm.pending(timers)) {
            let pending = TimerAlarm.pending(timers)
            guard !pending.isEmpty else { return }
            await TimerAlarm.play()
            // Restarted because another timer finished mid-beep: the new run alerts them all.
            guard !Task.isCancelled else { return }
            pending.sorted().forEach(onAlerted)
        }
    }
}

/// The kitchen-timer alarm.
///
/// Android plays its beeps on the *alarm* stream, which the ringer's silent mode does not
/// mute. The iOS counterpart is an audio session in the `.playback` category, which is not
/// silenced by the Ring/Silent switch — the same choice the Clock app's timer makes. A
/// system sound (`AudioServicesPlaySystemSound`) was the first version and was wrong for this:
/// it obeys the silent switch and the ringer volume, so a phone left on silent on the
/// counter would count a timer down to zero without a sound. Other audio (a podcast while
/// cooking) is ducked for the beeps, not stopped, and is told to resume afterwards.
///
/// The beep is synthesised rather than bundled, so there is no asset to ship or license.
@MainActor
enum TimerAlarm {
    /// Timers that have run out and not yet been announced.
    static func pending(_ timers: [Int: StepTimer]) -> Set<Int> {
        Set(timers.filter { $0.value.finished && !$0.value.alerted }.keys)
    }

    static let beepCount = 3
    static let beepInterval = Duration.milliseconds(600)

    /// Kept alive while it plays: AVAudioPlayer stops when released.
    private static var player: AVAudioPlayer?

    /// Three beeps, 600ms apart, like Android's `playAlarm`. Stops early, without playing
    /// the remaining beeps, when the calling task is cancelled.
    static func play() async {
        // Already cancelled (another timer finished first): don't take the audio session at
        // all, which would duck the user's podcast for nothing.
        guard !Task.isCancelled else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try session.setActive(true)
        } catch {
            // No audio available; the card still shows "Time's up".
        }
        defer {
            player?.stop()
            player = nil
            try? session.setActive(false, options: [.notifyOthersOnDeactivation])
        }
        guard let beep = try? AVAudioPlayer(data: beepWav) else { return }
        beep.volume = 0.9
        beep.prepareToPlay()
        player = beep
        await beeps {
            beep.currentTime = 0
            beep.play()
        }
    }

    /// The beep loop on its own, so a test can count beeps without a speaker or a stopwatch.
    /// A cancelled task plays nothing more: the sleep throws at once, and a loop that went on
    /// regardless would sound every remaining beep back to back.
    static func beeps(
        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        _ playOne: () -> Void
    ) async {
        for _ in 0 ..< beepCount {
            if Task.isCancelled { return }
            playOne()
            // The last beep still needs time to sound before the session is released.
            do { try await sleep(beepInterval) } catch { return }
        }
    }

    /// A 300ms, 880Hz sine beep with 10ms ramps (no click), as 16-bit mono PCM in a WAV.
    static let beepWav: Data = {
        let sampleRate = 44_100
        let frames = sampleRate * 300 / 1000
        let ramp = sampleRate / 100
        var samples = [Int16](repeating: 0, count: frames)
        for i in 0 ..< frames {
            let envelope = min(1, Double(min(i, frames - 1 - i)) / Double(ramp))
            let value = sin(2 * Double.pi * 880 * Double(i) / Double(sampleRate)) * envelope
            samples[i] = Int16(value * Double(Int16.max) * 0.8)
        }
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) {
            withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
        }
        let dataBytes = frames * 2
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36 + dataBytes))
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); append(UInt32(16))
        append(UInt16(1)); append(UInt16(1)) // PCM, mono
        append(UInt32(sampleRate)); append(UInt32(sampleRate * 2))
        append(UInt16(2)); append(UInt16(16)) // block align, bits per sample
        data.append(contentsOf: Array("data".utf8)); append(UInt32(dataBytes))
        samples.forEach { append($0) }
        return data
    }()
}

extension View {
    func timerAlerts(_ timers: [Int: StepTimer], onAlerted: @escaping (Int) -> Void) -> some View {
        modifier(TimerAlertsModifier(timers: timers, onAlerted: onAlerted))
    }
}
