package com.example.recipeclipper.ui.recipe

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * Plays the "time's up" beeps for any timer that just ran out, even if the user has left
 * cook mode. Marking a timer alerted only after the sound keeps a rotation from cutting
 * the alarm short, and stops it repeating.
 *
 * Lives here purely for tidiness — this is a view observing state and firing an effect,
 * which is correct MVVM as it stands. It must NOT move into the ViewModel, which may not
 * touch Android APIs. Not wrapped in an interface either: nothing tests it, and CLAUDE.md's
 * rule about adding seams only when a test needs one applies here.
 */
@Composable
internal fun TimerAlerts(timers: Map<Int, StepTimer>, onAlerted: (Int) -> Unit) {
    val pending = timers.filterValues { it.finished && !it.alerted }.keys
    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        playAlarm()
        pending.forEach(onAlerted)
    }
}

private suspend fun playAlarm() {
    val tone = try {
        ToneGenerator(AudioManager.STREAM_ALARM, 90)
    } catch (e: RuntimeException) {
        return // no audio available; the card still shows "Time's up"
    }
    try {
        repeat(3) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            delay(600)
        }
    } finally {
        tone.release()
    }
}
