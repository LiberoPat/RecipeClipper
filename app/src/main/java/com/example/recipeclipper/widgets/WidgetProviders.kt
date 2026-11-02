package com.example.recipeclipper.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the widget providers need from the graph (an entry point, as for the timer receivers). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun homeWidgets(): HomeWidgets
}

/**
 * The system asks for a redraw (placed, resized, every 30 minutes, after a reboot): every
 * widget is drawn by [HomeWidgets.update], off the main thread, with the receiver kept alive
 * until it finishes.
 */
abstract class HomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        redraw(context)

    private fun redraw(context: Context) {
        val widgets = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .homeWidgets()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                widgets.update()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Tonight (#309): today's planned meals; a tap opens the Week. */
class TonightWidgetProvider : HomeWidgetProvider()

/** The grocery list (#309): what's left to buy; a tap opens Groceries. */
class GroceriesWidgetProvider : HomeWidgetProvider()
