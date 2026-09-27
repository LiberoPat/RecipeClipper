# Release checklist

The steps only the owner can do: accounts, keys and store listings. The code
and config side is done: the IDs are `com.liberopat.recipeclipper` on both
platforms (iOS extension `.share`, UI tests `.uitests`), release signing reads
its keys from outside the repo, the iOS privacy manifest and export-compliance
key are in place, and `docs/privacy-policy.md` is drafted. The rest of the
plan is in issues #18 (iOS), #22 (Android), #20–#21 and #23.

## iOS

1. **Join the Apple Developer Program** ($99 a year) at
   developer.apple.com/programs.
2. **Set the Team ID.** It's the 10-character ID under Account → Membership
   details. Put it in `ios/project.yml`, `settings: base: DEVELOPMENT_TEAM`
   (the one place; every target inherits it), then `cd ios && xcodegen
   generate`. Signing is Automatic, so Xcode registers the two bundle IDs
   (`com.liberopat.recipeclipper` and `.share`) on first build to a device.
3. **Register the App Group.** The app and the share extension share their
   database and settings through `group.com.liberopat.recipeclipper`; without
   it, sharing to Recipe Clipper on a device can't save anything the app
   sees. On developer.apple.com → Certificates, Identifiers & Profiles →
   Identifiers → App Groups, add `group.com.liberopat.recipeclipper`, then
   enable the App Groups capability on both App IDs and tick that group.
   (Xcode's automatic signing usually does this on the first device build, as
   the entitlements already name it; check it did.) Then share a recipe from
   Safari on a device and confirm it appears in the app, and read the
   extension's memory in Console (see docs/testing.md).
4. **Register the iCloud container** (#150, the automatic backup copy). The
   app writes its latest export to `iCloud.com.liberopat.recipeclipper`, whose
   `Documents` folder Files shows as "Recipe Clipper". On developer.apple.com →
   Identifiers → iCloud Containers, add `iCloud.com.liberopat.recipeclipper`,
   then enable the iCloud capability (iCloud Documents; CloudKit isn't needed)
   on the app's App ID, not the extension's, and assign that container. (Xcode's
   automatic signing may do this on the first device build, as the entitlements
   already name it; check it did.) Until then the app runs normally and Settings
   says iCloud Drive isn't available. On a device signed in to iCloud with
   iCloud Drive on, open and leave the app, then check Files → iCloud Drive →
   Recipe Clipper for a `recipe-clipper-backup-….zip`. The folder's name shows
   in Files only after a build with a new `CURRENT_PROJECT_VERSION`.
5. **Reserve the name.** App Store Connect → Apps → + → New App, bundle ID
   `com.liberopat.recipeclipper`. The name must be unique on the store (#21).
6. **Privacy.** Enter the privacy policy URL (below). In App Privacy, answer
   "Data Not Collected".
7. **Build numbers.** Raise `CURRENT_PROJECT_VERSION` in `ios/project.yml`
   before every upload, and `MARKETING_VERSION` for each release.

## Android

1. **Create the keystore, once.** Keep it outside the repo:

   ```
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   mkdir -p ~/keys
   keytool -genkeypair -v -keystore ~/keys/recipeclipper-release.jks \
     -alias recipeclipper -keyalg RSA -keysize 4096 -validity 10000
   ```

   It asks for a password and a name. The keystore is PKCS12, which uses one
   password for the store and the key, so both properties below get the same
   one.
2. **Tell Gradle where it is.** Add to `~/.gradle/gradle.properties` (your
   home directory's, never the project's, which is committed):

   ```
   recipeClipper.storeFile=/Users/<you>/keys/recipeclipper-release.jks
   recipeClipper.storePassword=...
   recipeClipper.keyAlias=recipeclipper
   recipeClipper.keyPassword=...
   ```

   Or set `RECIPECLIPPER_STORE_FILE`, `RECIPECLIPPER_STORE_PASSWORD`,
   `RECIPECLIPPER_KEY_ALIAS` and `RECIPECLIPPER_KEY_PASSWORD` (for CI). Use an
   absolute path: `~` isn't expanded. Without all four, `assembleRelease`
   still works and produces `app-release-unsigned.apk`; with them, it produces
   a signed `app-release.apk`. Check it with
   `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.
3. **Back up the keystore and both passwords,** somewhere off this Mac (a
   password manager holds both). An update signed with any other key won't
   install over an earlier one, so losing it strands every installed copy.
4. **Reserve the app in Play Console** ($25 once) with application ID
   `com.liberopat.recipeclipper`. Play App Signing is the default: Google
   holds the key users' copies are signed with, and yours becomes the upload
   key (lost upload keys can be reset through Play support). APKs signed with
   your key and handed out directly (e.g. on GitHub Releases) then can't
   update a copy installed from Play, and the reverse; pick one channel per
   device.
5. **Target SDK.** Since 31 August 2026 Play accepts new apps and updates
   only if they target API 36 (Android 16) or higher; an extension to
   1 November 2026 can be requested in Play Console. The app targets 34, so
   raising it (with device testing) comes before the first upload (#23).
   Current rule: developer.android.com/google/play/requirements/target-sdk.
6. **Data safety form:** no data collected, no data shared. Add the privacy
   policy URL.
7. **Run the release build on a device** once before shipping (#22).

## Privacy policy URL

Both stores ask for one, even for an app that collects nothing. Publish
`docs/privacy-policy.md` somewhere public and stable (GitHub Pages on this
repo works), after filling in the date and a contact email, and put that URL
in App Store Connect and Play Console. Update it if the app ever starts
collecting or sending anything.
