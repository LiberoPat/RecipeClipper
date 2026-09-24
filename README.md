# Recipe Clipper

A tiny Android app that registers itself as a share target. Share a recipe
link to it from Chrome (or any browser/app), and it shows just the recipe —
title, ingredients, steps, times, servings — no life story, no ads, no SEO
filler.

## How it works

Almost every recipe website embeds a `schema.org/Recipe` JSON-LD block in the
page `<head>` — that's the structured data Google uses to build the recipe
rich-snippet cards in search results. It's already just the clean recipe
fields. This app fetches the shared page and reads that block directly
instead of scraping the visible article text, so it skips the surrounding
content entirely rather than trying to guess what to filter out.

If a site doesn't publish that data, the app says so rather than guessing at
a recipe from prose.

## Project layout

```
RecipeClipper/
├── app/src/main/java/com/example/recipeclipper/
│   ├── MainActivity.kt      # receives the shared URL (ACTION_SEND)
│   ├── RecipeScreen.kt      # Compose UI: URL entry, loading, error, recipe view
│   ├── RecipeParser.kt      # fetches the page + extracts the JSON-LD Recipe
│   └── RecipeModels.kt      # Recipe data class / ParseResult
└── app/src/main/AndroidManifest.xml   # the intent-filter that adds it to the share sheet
```

## Building it

1. Unzip this project.
2. Open the folder in Android Studio (File → Open). Let it sync — it'll
   offer to generate the Gradle wrapper if you don't already have one
   configured; accept that.
3. Run it on a device or emulator (min SDK 24 / Android 7.0+).

No API keys or backend needed — everything happens on-device.

## Trying it

1. Open Chrome (or any app) and go to a recipe page — e.g. a NYT Cooking,
   AllRecipes, Serious Eats, or Food Network recipe.
2. Tap Share → **Recipe Clipper**.
3. It fetches the page and shows the clean recipe. You can also just paste
   a URL into the text field at the top and tap Go.

## Known limitations (v1)

- Only reads JSON-LD `Recipe` data. Sites that only use old-style microdata
  (`itemprop="recipeIngredient"` etc.) or no structured data at all won't
  parse — I kept this version to the JSON-LD path since it covers the vast
  majority of recipe sites and keeps the parser simple. A microdata fallback
  would be a natural next step.
- No offline saving/favorites — it's a one-shot viewer.
- No dark theme handling, no adaptive launcher icon — cosmetic, easy to add.
- Fetches the page from Kotlin directly (Jsoup), so pages requiring login or
  heavy JavaScript-rendered content won't have their JSON-LD available.

## Ideas if you want to extend it

- Save parsed recipes locally (Room) so you build a personal recipe box.
- Add a microdata/plain-HTML fallback for sites without JSON-LD.
- Let the ingredient checkboxes persist and add a "scale servings" control.
- Ship it as a widget so a saved recipe's next step is visible while cooking.
