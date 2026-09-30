package com.example.recipeclipper.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hosts whose Cloudflare check (#220) the app's browser passed lately. A plain fetch of
 * such a host would only be refused (Cloudflare's `cf_clearance` cookie lives in the web view's
 * cookie store, and handing it to the plain fetch would be a fingerprint trick), so the
 * repository renders its pages first. An interface so the repository stays `Context`-free and
 * tests use an in-memory one. iOS has the same `ClearedHosts`.
 */
interface ClearedHosts {
    /** Whether [host] passed the check within the last [TTL_MS]. */
    fun isCleared(host: String): Boolean

    /** [host] just got past the check (or loaded on its clearance): remember it for [TTL_MS]. */
    fun record(host: String)

    companion object {
        /**
         * How long a pass is remembered: a day. Sites choose how long Cloudflare's clearance
         * lasts (often 30 minutes, up to a year); guessing long only costs a slower first try
         * when a site no longer checks, while guessing short costs the refused fetch and retry.
         */
        const val TTL_MS = 24 * 60 * 60 * 1000L

        /** Remembers nothing: the default for tests that aren't about it. */
        val None: ClearedHosts = object : ClearedHosts {
            override fun isCleared(host: String) = false
            override fun record(host: String) = Unit
        }
    }
}

/**
 * [ClearedHosts] in its own SharedPreferences file, `cloudflare_clearances`: each host's expiry,
 * in epoch millis. Deliberately **not backed up** (the backup rules are an include list): the
 * clearance it stands for is a cookie in this phone's web view, which a restore doesn't bring.
 * Expired entries are dropped whenever one is written.
 */
@Singleton
class SharedPrefsClearedHosts @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: Clock
) : ClearedHosts {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun isCleared(host: String): Boolean = prefs.getLong(host.lowercase(), 0L) > clock.now()

    override fun record(host: String) {
        val now = clock.now()
        prefs.edit {
            prefs.all.forEach { (key, value) -> if ((value as? Long ?: 0L) <= now) remove(key) }
            putLong(host.lowercase(), now + ClearedHosts.TTL_MS)
        }
    }

    companion object {
        const val FILE = "cloudflare_clearances"
    }
}
