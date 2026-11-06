package com.example.recipeclipper.ui.clip

import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View

/**
 * Keeps the app's own "Recipe Clipper" entry (`ProcessTextActivity`, #306) out of the clip
 * page's selection menu (#334): the page is already in the app. Wraps the callback the WebView
 * starts its selection mode with, and hides every item whose intent targets this package, each
 * time the menu is built or rebuilt (a selection change rebuilds it).
 */
class OwnProcessTextFilter(
    private val wrapped: ActionMode.Callback,
    private val packageName: String
) : ActionMode.Callback2() {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
        wrapped.onCreateActionMode(mode, menu).also { filter(menu) }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val changed = wrapped.onPrepareActionMode(mode, menu)
        filter(menu)
        return changed
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = wrapped.onActionItemClicked(mode, item)

    override fun onDestroyActionMode(mode: ActionMode) = wrapped.onDestroyActionMode(mode)

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        val callback2 = wrapped as? ActionMode.Callback2
        if (callback2 != null) callback2.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }

    /** Hides the items of [menu] that would open this app: its own text-processing entry. */
    fun filter(menu: Menu) {
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.intent?.component?.packageName == packageName) item.isVisible = false
        }
    }
}
