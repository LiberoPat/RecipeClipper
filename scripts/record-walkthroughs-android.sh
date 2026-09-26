#!/bin/bash
# Records the Android walkthrough videos (#106). Each walkthrough test records itself with
# `screenrecord` (WalkthroughBase); this installs the app and the test APK built with the
# walkthrough runner (-Pwalkthrough), clears the app before each test, runs it and pulls its
# video. Usage (from the repo root, with JAVA_HOME and ANDROID_HOME set):
#   scripts/record-walkthroughs-android.sh <adb-serial> [out-dir] [Class#test …]
# The device should be one nobody is using: it is switched to light mode and its app data wiped.
set -euo pipefail
SERIAL=${1:?adb serial}
OUT=${2:-$HOME/Downloads/RecipeClipper-walkthroughs}
shift $(( $# > 1 ? 2 : 1 ))
PKG=com.liberopat.recipeclipper
P=com.example.recipeclipper.walkthrough
TESTS=("$@")
[ ${#TESTS[@]} -eq 0 ] && TESTS=(
  MealPlanWalkthroughTest#test01_tabsAndWeek MealPlanWalkthroughTest#test02_groceries
  MealPlanWalkthroughTest#test03_pantryAndWhatINeed MealPlanWalkthroughTest#test04_weeklyMenus
  MealPlanWalkthroughTest#test05_expiryReminders RecipesWalkthroughTest#test06_recipesScreen
  RecipesWalkthroughTest#test07_amountsInSteps RecipesWalkthroughTest#test08_chefModeStubModel
  RecipesWalkthroughTest#test09_freeTier RecipesWalkthroughTest#test10_pageExtractionLine
  MealPlanWalkthroughTest#test11_groceriesAiMergingSimulated MealPlanWalkthroughTest#test12_groceriesJunkHidden)
mkdir -p "$OUT"
export ANDROID_SERIAL=$SERIAL
adb shell cmd uimode night no >/dev/null
./gradlew -q -Pwalkthrough installDebug installDebugAndroidTest

for t in "${TESTS[@]}"; do
  name=${t#*#}
  slug=$(echo "${name#test}" | sed -E 's/_/-/; s/([a-z])([A-Z])/\1-\2/g; s/([A-Z])([A-Z][a-z])/\1-\2/g' | tr 'A-Z' 'a-z')
  adb shell pm clear $PKG >/dev/null
  adb shell rm -f /data/local/tmp/rc-walkthrough.mp4
  result=$(adb shell am instrument -w -e class "$P.$t" $PKG.test/$P.WalkthroughRunner)
  if ! echo "$result" | grep -q "OK (1 test)"; then
    echo "FAILED: $t (screen at the miss: /tmp/android-$slug-miss.png)"
    echo "$result" | grep -m3 -E "Exception|Error|at com.example" || true
    adb pull /data/local/tmp/rc-walkthrough-miss.png "/tmp/android-$slug-miss.png" >/dev/null 2>&1 || true
    continue
  fi
  adb pull /data/local/tmp/rc-walkthrough.mp4 "/tmp/android-$slug-raw.mp4" >/dev/null
  # Smaller, and a phone-shaped 1280-high frame (macOS's avconvert; ffmpeg would do as well).
  avconvert --source "/tmp/android-$slug-raw.mp4" --preset Preset1280x720 \
    --output "$OUT/android-$slug.mp4" --replace >/dev/null
  echo "android-$slug.mp4"
done
