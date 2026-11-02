package com.example.recipeclipper

import android.content.Intent
import com.example.recipeclipper.data.model.ReceivedList
import com.example.recipeclipper.data.remote.SharedText
import com.example.recipeclipper.ui.navigation.Routes

/**
 * Text shared into the app: `ACTION_SEND` text/plain (a browser's link, a messaging app's
 * message, a note) or, behind the shareText flag (#306), `ACTION_PROCESS_TEXT` (text selected in
 * any app, "Recipe Clipper" in its selection menu).
 */
object TextIntent {

    /** Where shared text leads: a route to open, or #149's "Add this list" sheet. */
    sealed interface Target {
        data class Open(val route: String) : Target
        data class OfferList(val text: String) : Target
    }

    fun text(intent: Intent?, shareTextOn: Boolean): String? = when {
        intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        intent?.action == Intent.ACTION_PROCESS_TEXT && shareTextOn ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    /**
     * With shareText on (#306), a recipe in the text opens the editor to check, even beside a
     * link ([SharedText]), as does text that is neither a link nor a bulleted list. Otherwise,
     * as before: its first link imports, and its lines are offered as a list (#149).
     */
    fun target(text: String, shareTextOn: Boolean): Target? {
        if (text.isBlank()) return null
        if (shareTextOn) {
            when (SharedText.kind(text)) {
                SharedText.Kind.RECIPE -> return Target.Open(Routes.editText(text))
                SharedText.Kind.TEXT -> if (text.length <= SharedText.MAX_LENGTH) return Target.Open(Routes.editText(text))
                SharedText.Kind.LINK, SharedText.Kind.LIST -> Unit
            }
        }
        SharedText.link(text)?.let { return Target.Open(Routes.import(it)) }
        return if (ReceivedList.lines(text).isNotEmpty()) Target.OfferList(text) else null
    }
}
