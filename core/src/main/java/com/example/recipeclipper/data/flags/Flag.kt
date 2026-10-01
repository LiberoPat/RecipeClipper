package com.example.recipeclipper.data.flags

/**
 * Every feature flag (#87), one constant per entry in `shared/flags.json`, which both apps
 * read. `FeatureFlagRegistryTest` fails if the two lists differ, or if a flag is no longer
 * referenced anywhere: retiring a flag deletes it here, in flags.json and its branches in one PR.
 */
enum class Flag(val key: String) {
    /** Chef mode (#100): the Settings switch for short steps written on the device. */
    CHEF_MODE("chefMode"),

    /** The free tier (#107): 20 recipes, and the one-time unlock for unlimited ones. */
    FREE_TIER("freeTier"),

    /** A recipe picked from the page's text by the on-device model when the page has no recipe data (#103). */
    LLM_EXTRACTION("llmExtraction"),

    /** Typed decisions by the on-device model: close pantry names, aisles (#104). */
    AI_DECISIONS("aiDecisions"),

    /** With [AI_DECISIONS], the count-bracket decision too (#104); off since #127, kept to re-measure. */
    AI_COUNT_BRACKETS("aiCountBrackets"),

    /** Reddit posts (#11): Reddit links go to the Reddit source rather than the blog one. */
    REDDIT("reddit"),

    /** Reading a Reddit post's photo with on-device text recognition, checked by the cook (#198),
     *  and scanning the cook's own photos the same way (#226). */
    PHOTO_TEXT("photoText");

    companion object {
        fun forKey(key: String): Flag? = entries.firstOrNull { it.key == key }
    }
}
