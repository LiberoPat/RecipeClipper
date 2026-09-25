package com.example.recipeclipper

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * For the Robolectric screen tests (#91): runs what the main looper holds for the next
 * [millis]. A `delay` or `debounce` in `viewModelScope` waits on the main looper, whose clock
 * under Robolectric moves only when a test moves it (on a device, the screen's own animations
 * took long enough in real time). Compose's clock is separate and moves by itself.
 */
fun advanceMainLooperBy(millis: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
}

/** The 250 ms search debounce in History and the Week's add sheet. */
fun passTheSearchDebounce() = advanceMainLooperBy(250)
