package com.example.recipeclipper.widgets

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.recipeclipper.R
import org.robolectric.Shadows.shadowOf

/** The launcher, for the widget tests (Robolectric's): places widgets and reads what they show. */
internal class WidgetHost(private val app: Application) {
    private val manager = AppWidgetManager.getInstance(app)

    fun place(provider: Class<*>, id: Int): Int {
        val component = ComponentName(app, provider)
        shadowOf(manager).addInstalledProvider(AppWidgetProviderInfo().apply {
            this.provider = component
            initialLayout = R.layout.widget_list
        })
        shadowOf(manager).setAllowedToBindAppWidgets(true)
        manager.bindAppWidgetIdIfAllowed(id, component)
        return id
    }

    /** The widget's height in dp as the launcher reports it: the most (portrait), the least (landscape). */
    fun resize(id: Int, portrait: Int, landscape: Int = portrait) {
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, portrait)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, landscape)
        })
    }

    /** Inflates the widget again from what was last drawn, as a launcher does when the phone is
     *  turned or the theme changes: nothing is asked of the app. */
    fun inflateAgain(id: Int) = shadowOf(manager).reconstructWidgetViewAsIfPhoneWasRotated(id)

    fun view(id: Int): View = shadowOf(manager).getViewFor(id)

    /** Every visible text, top to bottom. */
    fun texts(id: Int): List<String> = textViews(id).map { it.text.toString() }

    fun textViews(id: Int): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView) out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(view(id))
        return out
    }
}
