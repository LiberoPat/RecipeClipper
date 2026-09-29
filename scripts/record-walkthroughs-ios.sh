#!/bin/bash
# Records the iOS walkthrough videos (#106): each WalkthroughUITests test under
# `simctl io recordVideo`, trimmed to the test's WALKTHROUGH-START/END marks and re-encoded
# with avconvert. Usage (from the repo root):
#   scripts/record-walkthroughs-ios.sh <simulator-udid> [out-dir] [test-name …]
# The simulator should be one of your own, booted, iPhone-sized; it is switched to light mode.
set -euo pipefail
SIM=${1:?simulator UDID}
OUT=${2:-$HOME/Downloads/RecipeClipper-walkthroughs}
shift $(( $# > 1 ? 2 : 1 ))
DD=${DERIVED_DATA:-/tmp/rc-walkthrough-dd}
TESTS=("$@")
[ ${#TESTS[@]} -eq 0 ] && TESTS=(test01_tabsAndWeek test02_groceries test03_pantryAndWhatINeed test04_weeklyMenus
  test05_expiryReminders test06_recipesScreen test07_amountsInSteps test08_chefModeStubModel test09_freeTier
  test10_pageExtractionLine test11_groceriesAiMergingSimulated test12_groceriesJunkHidden test13_iMadeThis
  test14_automaticBackup test15_sendAndPasteAList test16_sendAndReceiveAFile test17_firstRunTour
  test18_doneShoppingAndOnList test19_pantrySendList test20_pantryUseUpAfterCooking test21_chefModeUnsupportedSimulated
  test22_recipeJunkHidden test23_markAsCooked test24_tooltips test25_pantryStates test26_onionPlurals
  test27_redditImport test28_readThePhoto test29_readingOtherLanguages)
mkdir -p "$OUT" "$DD/raw"
xcrun simctl ui "$SIM" appearance light
# A new simulator's keyboard shows its slide-to-type introduction once, over the app: mark it seen.
xcrun simctl spawn "$SIM" defaults write com.apple.keyboard.preferences DidShowContinuousPathIntroduction -bool true
# The photo "I made this" picks from the library (#116): a macOS sample picture, through BMP so
# it loses its capture date and Photos files it under today, first in the picker.
if [[ " ${TESTS[*]} " == *" test13_iMadeThis "* ]]; then
  photo=$(mktemp -d)/photo
  sips -s format bmp "/Library/User Pictures/Fun/Gingerbread Man.heic" --out "$photo.bmp" >/dev/null 2>&1 &&
    sips -s format png "$photo.bmp" --out "$photo.png" >/dev/null 2>&1 &&
    xcrun simctl addmedia "$SIM" "$photo.png"
fi
(cd ios && xcodebuild -project RecipeClipper.xcodeproj -scheme RecipeClipper -destination "id=$SIM" \
  -derivedDataPath "$DD" build-for-testing -quiet)

for t in "${TESTS[@]}"; do
  # test03_pantryAndWhatINeed -> 03-pantry-and-what-i-need
  slug=$(echo "${t#test}" | sed -E 's/_/-/; s/([a-z])([A-Z])/\1-\2/g; s/([A-Z])([A-Z][a-z])/\1-\2/g' | tr 'A-Z' 'a-z')
  raw="$DD/raw/$slug.mp4"; log="$DD/raw/$slug.log"
  rm -f "$raw"
  xcrun simctl io "$SIM" recordVideo --codec h264 --force "$raw" 2>/dev/null &
  rec=$!
  sleep 1
  began=$(python3 -c 'import time; print(time.time())')
  (cd ios && TEST_RUNNER_RC_WALKTHROUGH=1 xcodebuild -project RecipeClipper.xcodeproj -scheme RecipeClipper \
    -destination "id=$SIM" -derivedDataPath "$DD" -collect-test-diagnostics never test-without-building \
    "-only-testing:RecipeClipperUITests/WalkthroughUITests/$t" > "$log" 2>&1) || echo "FAILED: $t (see $log)"
  kill -INT $rec 2>/dev/null || true; wait $rec || true
  start=$(grep -o 'WALKTHROUGH-START [0-9.]*' "$log" | head -1 | cut -d' ' -f2)
  end=$(grep -o 'WALKTHROUGH-END [0-9.]*' "$log" | tail -1 | cut -d' ' -f2)
  if [ -z "$start" ] || [ -z "$end" ]; then echo "no marks: $t"; continue; fi
  from=$(python3 -c "print(max(0, $start - $began - 0.5))")
  length=$(python3 -c "print($end - $start + 0.3)")
  avconvert --source "$raw" --preset Preset1280x720 --start "$from" --duration "$length" \
    --output "$OUT/ios-$slug.mp4" --replace >/dev/null
  echo "ios-$slug.mp4 ${length%.*}s"
done
