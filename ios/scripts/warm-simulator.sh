#!/bin/bash
# Warms a freshly booted simulator before the UI tests (.github/workflows/ios-ui-tests.yml).
#
#   ios/scripts/warm-simulator.sh <udid> <path/to/RecipeClipper.app> [budget-seconds]
#
# A fresh simulator spends its first minutes on first-boot work (wallpapers and widgets drawn,
# Shortcuts, sync and engagement daemons): about eight CPU-minutes on a 10-core Mac, all done
# within 2.5 minutes there, but on the CI's few cores it overlapped the first ten-odd minutes of
# tests, whose XCTest steps then took 3-13 s each (docs/testing.md, "CI"). So, in order:
#
# 1. Wait until boot has really finished (`bootstatus -b`).
# 2. Install the app and launch it once, as a test would (`-uiTestSeed standard`: an in-memory
#    database and the test preferences suite, so nothing is left behind), so its first launch's
#    work (frameworks, fonts, caches) is done here; then terminate and uninstall it, leaving the
#    simulator as the tests always had it, without the app.
# 3. Wait until the simulator's own processes (everything under this device's launchd_sim) are
#    quiet: under QUIET_CORES of CPU for three samples in a row (15 s), measured from their CPU
#    time. The whole warm-up stops waiting at budget-seconds (default 165) from its start, so it
#    never costs much more than three minutes; a capped wait says so and the tests go ahead.
#
# It prints a sample every 15 s, and one summary line last (also to $GITHUB_STEP_SUMMARY when set).
# It never fails the job: a capped wait or a failed install or launch is reported and the tests
# go ahead (only bootstatus failing, a simulator that never booted, exits non-zero).

set -euo pipefail

udid=${1:?usage: warm-simulator.sh <udid> <app> [budget-seconds]}
app=${2:?usage: warm-simulator.sh <udid> <app> [budget-seconds]}
budget=${3:-165}
QUIET_CORES=${QUIET_CORES:-0.3}
INTERVAL=5
QUIET_SAMPLES=3

start=$SECONDS
xcrun simctl bootstatus "$udid" -b >/dev/null
booted=$SECONDS

bundle=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")
# A failed install or launch only costs the warm-up: say so and still wait for quiet.
if ! { xcrun simctl install "$udid" "$app" && xcrun simctl launch "$udid" "$bundle" -uiTestSeed standard >/dev/null; }; then
  echo "::warning title=Simulator warm-up::The app didn't install or launch; waiting for quiet without it."
fi
launched=$SECONDS

# Total CPU seconds used so far by this device's launchd_sim and everything under it.
sim_cpu_seconds() {
  local root
  root=$(pgrep -f "launchd_sim .*/$udid/" | head -n 1 || true)
  [ -n "$root" ] || { echo 0; return; }
  ps -A -o pid=,ppid=,cputime= | awk -v root="$root" '
    {
      n = split($3, part, ":"); s = 0
      for (i = 1; i <= n; i++) s = s * 60 + part[i]
      parent[$1] = $2; cpu[$1] = s
    }
    END {
      total = 0
      for (p in cpu) {
        q = p
        for (depth = 0; depth < 64 && q != "" && q != 0 && q != 1; depth++) {
          if (q == root) { total += cpu[p]; break }
          q = parent[q]
        }
      }
      printf "%.2f\n", total
    }'
}

# Wait for quiet with the app still running, so its own settling counts, then let it go.
outcome="still busy, capped at ${budget}s"
quiet=0
last=$(sim_cpu_seconds)
waited=0
while [ $((SECONDS - start)) -lt "$budget" ]; do
  sleep "$INTERVAL"
  waited=$((waited + INTERVAL))
  now=$(sim_cpu_seconds)
  cores=$(awk -v a="$last" -v b="$now" -v t="$INTERVAL" 'BEGIN { d = (b - a) / t; if (d < 0) d = 0; printf "%.2f", d }')
  last=$now
  if awk -v c="$cores" -v q="$QUIET_CORES" 'BEGIN { exit !(c < q) }'; then
    quiet=$((quiet + 1))
  else
    quiet=0
  fi
  if [ $((waited % 15)) -eq 0 ] || [ "$quiet" -ge "$QUIET_SAMPLES" ]; then
    echo "warm-simulator: ${waited}s: the simulator used ${cores} cores"
  fi
  if [ "$quiet" -ge "$QUIET_SAMPLES" ]; then
    outcome="quiet after ${waited}s"
    break
  fi
done

xcrun simctl terminate "$udid" "$bundle" >/dev/null 2>&1 || true
xcrun simctl uninstall "$udid" "$bundle" 2>/dev/null || true

line="Simulator warm-up: $((SECONDS - start))s in all (boot finishing $((booted - start))s, app install and launch $((launched - booted))s, then $outcome, under $QUIET_CORES cores)."
echo "warm-simulator: $line"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "$line" >> "$GITHUB_STEP_SUMMARY"
fi
