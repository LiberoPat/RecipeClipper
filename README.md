# Recipe Clipper

Share a recipe link, get just the recipe: ingredients, steps, times and servings, with none
of the life story, ads or SEO filler. Recipe Clipper is a share target on **Android**
(Kotlin, Jetpack Compose) and **iOS** (SwiftUI). Share a link to it from the browser or any
app, and it opens the clean recipe.

No accounts, API keys or backend. Everything happens on the device.

## What it does

- **Clean recipes from a shared link.** Share a page to Recipe Clipper, or paste a link on
  the home screen.
- **History, automatically.** Every recipe you open is kept, newest first. History holds
  the 50 most recent, and it is searchable by title or ingredient. Swipe a recipe away to
  delete it, with undo.
- **Lists, deliberately.** Save a recipe to one or more lists from the bookmark icon. The app
  starts with Favorites, Breakfast, Lunch, Dinner, Desserts and Snacks, and you can make,
  rename and delete your own. Favorites is the only list that can't be deleted. A recipe in
  any list is never dropped from history.
- **Works offline.** Anything you've opened once opens again without a connection, photos
  included.
- **Scale servings.** A `Serves − 6 +` stepper rescales every ingredient amount.
- **Convert units.** Show amounts as written, in grams, in ounces, or in metric, and oven
  temperatures as written, in °C or in °F. The unit choice is set once and applies to every
  recipe. Only conversions that are reliable are made: an ingredient whose weight varies too
  much (salt, chopped vegetables, nuts) stays as written rather than showing a confident
  wrong number.
- **Cook mode.** The steps become a highlighted list: done steps are dimmed, the current
  step is enlarged, and the next ones stay readable. Tap any step to jump to it. Timers are
  picked up from the step text ("bake for 20 minutes"), several can run at once, and each
  beeps when it's done. The screen stays on while you cook, and ticked ingredients are
  saved.
- **Share a recipe out** as plain text, scaled and converted as you see it, to Messages,
  WhatsApp, Mail and so on.
- **Clear errors, easy retries.** If a site refuses the request, you're offline, or a page
  has no recipe, the app says which, and every error has Try again. A refused request is
  retried once automatically, and an offline recipe loads by itself when the connection
  returns.
- **Dark mode** follows the system, with an optional "dark while cooking". On iOS the text
  follows the system text size (Dynamic Type).

## How it works

Almost every recipe site embeds a `schema.org/Recipe` JSON-LD block in the page. That is the
structured data Google uses for recipe cards in search results, and it already holds just
the recipe. Recipe Clipper fetches the shared page and reads that block, rather than
scraping the article and guessing what to filter out. If a page doesn't publish it, the app
says so instead of guessing at a recipe from prose.

## Repository layout

```
RecipeClipper/
├── app/          Android app: MVVM, Hilt, Room, Compose Navigation
├── ios/          iOS app: SwiftUI, iOS 17+, no third-party dependencies
├── CLAUDE.md     the spec for both platforms: product rules, UI decisions,
│                 unit-conversion rules, schema, architecture and testing notes
└── ios/README.md what is specific to iOS
```

The two apps behave the same. The shared logic (parsing, scaling, unit conversion) is
checked against the Kotlin by a differential test corpus on iOS.

## Building

**Android** (Android 7.0, API 24, or later). Open the project in Android Studio and run it,
or from a terminal with Android Studio's bundled JDK:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug        # build and install on a connected device or emulator
./gradlew testDebugUnitTest   # unit tests
```

**iOS** (iOS 17 or later). The Xcode project is generated from `ios/project.yml` by
[XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`):

```
cd ios
xcodegen generate
open RecipeClipper.xcodeproj
```

To run on an iPhone rather than the simulator, choose your team under Signing &
Capabilities for both the app and the `RecipeClipperShare` extension. See `ios/README.md`
for tests and debug launch options.

## Known limitations

- **Only JSON-LD recipes.** A site that publishes its recipe only as older microdata, or
  not at all, shows "no recipe found". Smitten Kitchen is one such site.
- **Sites sometimes refuse the request.** Many recipe sites block automated requests on and
  off: the same site can refuse one minute and answer the next. Try again usually works.
  There is no user agent that always gets through.
- **No login or JavaScript-rendered pages.** Pages behind a login, or that build their
  content with JavaScript, don't expose their recipe data to the app.
- **Reddit links aren't supported yet.**
- **Some cook-mode progress isn't saved yet.** Closing the app loses the current step, the
  running timers and the chosen serving size. Ticked ingredients are kept.
- **Timer alarms need the app open.** They're reliable while cook mode is on screen, and cook
  mode keeps the screen awake. With the app in the background an alarm may not sound, and
  there is no notification.
- **English only.**
- On iOS, the share extension opens the app through a workaround, since iOS has no
  supported way to do it. A future iOS version could break it (see `ios/README.md`).

## Roadmap

- Reddit posts, including finding a recipe transcribed in the comments.
- Saving cook-mode progress and the chosen serving size.
- Timer alarms that work in the background, with a notification.
