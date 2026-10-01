package com.example.recipeclipper.data.remote

/**
 * Reddit `.json` listings (`shared/fixtures/reddit`, which the iOS suite reads too), in the
 * shape Reddit really returns with `raw_json=1`: the `[post listing, comment listing]` pair,
 * `t3`/`t1`/`more` kinds, `replies` as `""` or a listing, `is_submitter`, a stickied
 * AutoModerator comment first, gallery `media_metadata`, `crosspost_parent` and its list. The
 * shapes were checked against recorded responses (PRAW's test cassettes and archived
 * r/recipes listings); the posts, people and recipes are made up, and most fields the parser
 * doesn't read are cut.
 */
object RedditFixtures {

    /** A photo on r/recipes, the recipe in the poster's comment (below AutoModerator's
     *  template and another reader's shorter recipe). */
    val IMAGE_WITH_OP_RECIPE get() = read("recipes-image-op-comment")

    /** A text post whose body is the recipe, with bold "Ingredients:" headers, "•" bullets,
     *  hard line breaks and a trailing note and edit. */
    val SELF_POST get() = read("recipes-self-post")

    /** A gallery of a recipe card on r/Old_Recipes, transcribed in a reply to AutoModerator
     *  in capitals, with Reddit's escaped "1\." steps. */
    val CARD_WITH_TRANSCRIPTION get() = read("old-recipes-card-transcription")

    /** A gallery of a recipe card (front, then back) that nobody has transcribed: only
     *  AutoModerator's request and chatter. What "Read the photo" (#198) is for. */
    val CARD_UNTRANSCRIBED get() = read("old-recipes-card-untranscribed")

    /** A photo of a finished dish: only chatter, the poster's included. */
    val PHOTO_ONLY get() = read("food-photo-chatter")

    /** A crosspost of [IMAGE_WITH_OP_RECIPE]: no body, and none of its comments is a recipe. */
    val CROSSPOST get() = read("crosspost")

    /** A request answered by a comment whose ingredients have no header above them. */
    val REPLY_WITHOUT_HEADERS get() = read("request-reply-no-headers")

    private fun read(name: String): String =
        RedditFixtures::class.java.getResourceAsStream("/reddit/$name.json")!!.bufferedReader().use { it.readText() }
}
