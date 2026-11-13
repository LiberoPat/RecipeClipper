package com.example.recipeclipper.ui.edit

import com.example.recipeclipper.data.remote.SharedText
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text shared in for the editor (#306), waiting for its review to open. MainActivity reads the
 * text once ([SharedText.route]) and leaves it here with its reading, under a short key that
 * rides in the route (`edit/text?key=…`); [EditRecipeViewModel] takes it. The text itself never
 * rides in the route: a route is saved state, and past about 117,000 encoded characters (16,000
 * in Japanese) it stopped matching and crashed the app (#334). In memory only, like
 * `ReceivedListInbox`: once taken, the review keeps the text in its own saved state. A new share
 * replaces one still waiting.
 */
@Singleton
class SharedTextInbox @Inject constructor() {

    /** The text as shared, and its reading, done once. */
    class Entry(val text: String, val reading: SharedText.Reading)

    private val entries = HashMap<String, Entry>()
    private val keys = AtomicLong()

    /** Leaves [text] and its [reading] for the editor; the key for its route. */
    @Synchronized
    fun offer(text: String, reading: SharedText.Reading): String {
        entries.clear()
        val key = "t${keys.incrementAndGet()}"
        entries[key] = Entry(text, reading)
        return key
    }

    /** The entry left under [key], once: taking it empties the inbox. */
    @Synchronized
    fun take(key: String): Entry? = entries.remove(key)
}
