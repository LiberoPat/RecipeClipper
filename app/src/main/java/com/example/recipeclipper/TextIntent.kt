package com.example.recipeclipper

import android.content.Intent
import com.example.recipeclipper.data.model.ReceivedList
import com.example.recipeclipper.data.remote.SharedText
import com.example.recipeclipper.ui.navigation.Routes

/**
 * Text shared into the app: `ACTION_SEND` text/plain (a browser's link, a messaging app's
 * message, a note), the text beside a shared picture, or, behind the shareText flag (#306),
 * `ACTION_PROCESS_TEXT` (text selected in any app, "Recipe Clipper" in its selection menu,
 * through [ProcessTextActivity]).
 */
object TextIntent {

    /** Where shared text leads: a route to open, #149's "Add this list" sheet, or the editor's review. */
    sealed interface Target {
        data class Open(val route: String) : Target
        data class OfferList(val text: String) : Target

        /** The editor on [text], read once ([reading]); MainActivity leaves it in `SharedTextInbox`. */
        data class Review(val text: String, val reading: SharedText.Reading) : Target
    }

    /** The text [intent] carries, or null. A picture's text (#334) and a selection need the flag. */
    fun text(intent: Intent?, shareTextOn: Boolean): String? = when {
        intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        intent?.type?.startsWith("image/") == true && shareTextOn &&
            (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) ->
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        intent?.action == Intent.ACTION_PROCESS_TEXT && shareTextOn ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    /**
     * With shareText on (#306), where [SharedText.route] says: a recipe opens the editor to check,
     * even beside a link, as does text that is neither a link nor a list; a link imports; a list
     * is offered. Beside pictures ([withPictures], #334) only a recipe wins; anything else leaves
     * the pictures to the scan. Otherwise, as before #306: the first link imports, and lines are
     * offered as a list (#149).
     */
    fun target(text: String, shareTextOn: Boolean, withPictures: Boolean = false): Target? {
        if (shareTextOn) {
            return when (val route = SharedText.route(text)) {
                is SharedText.Route.Editor ->
                    if (route.reading.sorted || !withPictures) Target.Review(text, route.reading) else null
                is SharedText.Route.Link -> if (withPictures) null else Target.Open(Routes.import(route.url))
                SharedText.Route.List -> if (withPictures) null else Target.OfferList(text)
                SharedText.Route.None -> null
            }
        }
        if (text.isBlank()) return null
        SharedText.link(text)?.let { return Target.Open(Routes.import(it)) }
        return if (ReceivedList.lines(text).isNotEmpty()) Target.OfferList(text) else null
    }
}
