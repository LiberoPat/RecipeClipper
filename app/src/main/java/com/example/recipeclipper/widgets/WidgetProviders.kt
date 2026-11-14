package com.example.recipeclipper.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import com.example.recipeclipper.runAsync
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** What the widget providers need from the graph (an entry point, as for the timer receivers). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun homeWidgets(): HomeWidgets
}

/** Every widget is drawn by [HomeWidgets.update], off the main thread, with the receiver kept
 *  alive until it finishes. */
internal fun BroadcastReceiver.redrawWidgets(context: Context) {
    val widgets = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        .homeWidgets()
    runAsync { widgets.update() }
}

/**
 * The system asks for a redraw: a widget was placed or resized, or the phone restarted. There
 * is no periodic refresh (#329): the app redraws when the content changes, and
 * [WidgetDayChange] when the day does. A widget taken away redraws too, since [HomeWidgets]
 * follows the data and the day only while one is placed.
 */
abstract class HomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redrawWidgets(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        redrawWidgets(context)

    override fun onDeleted(context: Context, ids: IntArray) = redrawWidgets(context)
}

/** Tonight (#309): today's planned meals; a tap opens the Week. */
class TonightWidgetProvider : HomeWidgetProvider()

/** The grocery list (#309): what's left to buy; a tap opens Groceries. */
class GroceriesWidgetProvider : HomeWidgetProvider()
