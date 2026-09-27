package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.ClipDraft
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unsaved "Clip it yourself" work, kept for the session so leaving and reopening the same page
 * picks up where the user stopped (#37, owner decision 9). Keyed by the cleaned URL. In memory
 * only, never written to disk: a draft is discarded after Save or an explicit Discard, and
 * lost with the process.
 */
@Singleton
class ClipDraftStore @Inject constructor() {

    private val drafts = mutableMapOf<String, ClipDraft>()

    @Synchronized
    fun get(url: String): ClipDraft? = drafts[url]

    /** Keeps [draft] under its URL, or forgets the URL when there is nothing in it. */
    @Synchronized
    fun put(draft: ClipDraft) {
        if (draft.isEmpty) drafts.remove(draft.sourceUrl) else drafts[draft.sourceUrl] = draft
    }

    @Synchronized
    fun remove(url: String) {
        drafts.remove(url)
    }
}
