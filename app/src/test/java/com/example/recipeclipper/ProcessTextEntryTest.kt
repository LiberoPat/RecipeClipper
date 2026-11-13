package com.example.recipeclipper

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.ui.clip.OwnProcessTextFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** "Recipe Clipper" in text-selection menus (#306): offered with the shareText flag, never in the app's own (#334). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProcessTextEntryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)

    private fun resolvesInMenus(): Boolean {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    @Test
    fun theEntryFollowsTheFlag() = runTest {
        val entry = ProcessTextEntry(context, flags)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        entry.start(scope)
        scope.advanceUntilIdle()
        assertTrue(entry.isOffered())
        assertTrue(resolvesInMenus())

        flags.set(Flag.SHARE_TEXT, false)
        scope.advanceUntilIdle()
        assertFalse(entry.isOffered())
        assertFalse(resolvesInMenus())

        flags.set(Flag.SHARE_TEXT, true)
        scope.advanceUntilIdle()
        assertTrue(entry.isOffered())
        assertTrue(resolvesInMenus())
    }

    @Test
    fun theAppsOwnSelectionMenuLeavesItsEntryOut() {
        val menu = PopupMenu(context, View(context)).menu
        val own = menu.add("Recipe Clipper").setIntent(Intent(context, ProcessTextActivity::class.java))
        val other = menu.add("Translate").setIntent(
            Intent(Intent.ACTION_PROCESS_TEXT).setClassName("com.example.other", "com.example.other.Translate")
        )
        val plain = menu.add("Copy")

        OwnProcessTextFilter(NoCallback, context.packageName).filter(menu)

        assertFalse(own.isVisible)
        assertTrue(other.isVisible)
        assertTrue(plain.isVisible)
    }

    private object NoCallback : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = true
        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
        override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
    }
}
