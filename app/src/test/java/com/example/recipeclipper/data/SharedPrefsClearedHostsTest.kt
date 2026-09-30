package com.example.recipeclipper.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The hosts that passed Cloudflare's check (#220), kept for a day in their own file. */
@RunWith(AndroidJUnit4::class)
class SharedPrefsClearedHostsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000L
    private val hosts = SharedPrefsClearedHosts(context, Clock { now })

    @Test fun `a recorded host is cleared for a day, then not`() {
        assertFalse(hosts.isCleared("recipes.example.test"))
        hosts.record("Recipes.Example.Test")
        assertTrue(hosts.isCleared("recipes.example.test"))

        now += ClearedHosts.TTL_MS - 1
        assertTrue(hosts.isCleared("recipes.example.test"))
        now += 1
        assertFalse(hosts.isCleared("recipes.example.test"))
    }

    @Test fun `it survives a new instance, and expired hosts are dropped on the next record`() {
        hosts.record("old.example.test")
        now += ClearedHosts.TTL_MS
        SharedPrefsClearedHosts(context, Clock { now }).record("new.example.test")

        val file = context.getSharedPreferences(SharedPrefsClearedHosts.FILE, Context.MODE_PRIVATE)
        assertEquals(setOf("new.example.test"), file.all.keys)
        assertTrue(SharedPrefsClearedHosts(context, Clock { now }).isCleared("new.example.test"))
    }
}
