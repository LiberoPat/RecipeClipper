#!/bin/bash
# Runs one shard's UI tests for .github/workflows/ios-ui-tests.yml, from ios/, on a simulator
# that is already booted and warmed (ios/scripts/warm-simulator.sh).
#
#   ios/scripts/run-ui-tests.sh <udid> <-only-testing:… arguments, as ui-test-shard.sh prints them>
#   ios/scripts/run-ui-tests.sh --report <xcodebuild-log>...   # only the retry report, for logs
#
# - A failed test runs once more at once (`-retry-tests-on-failure -test-iterations 2`), so a test
#   fails the shard only if it fails twice in a row: on CI, a fresh simulator's first ten-odd
#   minutes make single XCTest steps take 3-13 s, in whichever class runs then.
# - Retries are never silent: every test that needed one is listed in the job summary
#   ($GITHUB_STEP_SUMMARY) and raised as a ::warning:: with its first failure, so a repeat offender
#   can be noted on #395. A test that failed both times is an ::error:: and fails the shard.
# - The test runner crashing before any test ran ("never finished bootstrapping", #296) is the
#   simulator, not a test: the simulator is rebooted and the shard runs once more.
#
# Each test gets five minutes (ten at most), so one stuck test fails alone instead of running the
# job into its timeout. No diagnostics from the simulator: collecting them after a failure timed
# out at ten minutes every time. Logs: build/ui-tests[-rerun].log; results:
# build/RecipeClipperUITests[-rerun].xcresult.

set -uo pipefail

# What needed a retry, and what failed for good, from the logs' "Test Case … passed/failed" lines
# and the error lines before them.
report() {
  python3 -I - "$@" <<'PY'
import collections
import os
import re
import sys

CASE = re.compile(r"Test Case '-\[(\w+)\.(\w+) (\w+)\]' (passed|failed|skipped) \(([\d.]+) seconds\)")
ERROR = re.compile(r"^(\S+?\.swift):(\d+): error: -\[(\w+)\.(\w+) (\w+)\] : (.*)$")

runs = collections.OrderedDict()   # "Class.test" -> ["failed", "passed", ...]
first_error = {}                   # "Class.test" -> (file, line, message), its first failure's
for path in sys.argv[1:]:
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except FileNotFoundError:
        continue
    for line in lines:
        m = ERROR.match(line)
        if m:
            key = m.group(4) + "." + m.group(5)
            if key not in first_error:
                file = m.group(1)
                file = file[file.index("/ios/") + 1:] if "/ios/" in file else os.path.basename(file)
                first_error[key] = (file, m.group(2), m.group(6))
            continue
        m = CASE.search(line)
        if m:
            runs.setdefault(m.group(2) + "." + m.group(3), []).append(m.group(4))

retried = [k for k, r in runs.items() if r[-1] != "failed" and "failed" in r]
failed = [k for k, r in runs.items() if r[-1] == "failed"]


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotate(level, key, title, text):
    where = ""
    if key in first_error:
        file, line, message = first_error[key]
        where = "file=%s,line=%s," % (file, line)
        text += ": " + message
    print("::%s %stitle=%s::%s" % (level, where, title, escape(text)))


summary = []
if retried:
    summary.append("#### Passed on retry (note each on #395)")
    for key in retried:
        detail = first_error.get(key)
        summary.append("- `%s` failed, then passed%s" % (key, (": %s:%s %s" % detail) if detail else ""))
        annotate("warning", key, "UI test passed on retry",
                 "%s failed, then passed on retry (note it on #395)" % key)
if failed:
    summary.append("#### Failed on every try")
    for key in failed:
        detail = first_error.get(key)
        tries = len(runs[key])
        summary.append("- `%s` failed %s%s" % (
            key, "twice" if tries == 2 else "once" if tries == 1 else "%d times" % tries,
            (": %s:%s %s" % detail) if detail else ""))
        annotate("error", key, "UI test failed on every try", "%s failed on every try" % key)
if runs:
    summary.append("%d tests: %d passed first time, %d passed on retry, %d failed." % (
        len(runs), sum(1 for r in runs.values() if "failed" not in r), len(retried), len(failed)))
else:
    summary.append("No test results in the log.")

text = "\n".join(summary)
print(text)
target = os.environ.get("GITHUB_STEP_SUMMARY")
if target:
    with open(target, "a", encoding="utf-8") as f:
        f.write(text + "\n")
# The workflow uploads the result bundle when a test needed a retry, not only on failure.
output = os.environ.get("GITHUB_OUTPUT")
if output and retried:
    with open(output, "a", encoding="utf-8") as f:
        f.write("retried=true\n")
PY
}

if [ "${1:-}" = "--report" ]; then
  shift
  report "$@"
  exit
fi

udid=${1:?usage: run-ui-tests.sh <udid> <-only-testing:… arguments>}
shift

# $1 is the log's and result bundle's suffix; the rest go to xcodebuild.
run_tests() {
  local suffix=$1
  shift
  xcodebuild test-without-building \
    -project RecipeClipper.xcodeproj -scheme RecipeClipper \
    -destination "id=$udid" -derivedDataPath build/DerivedData \
    "$@" -parallel-testing-enabled NO \
    -retry-tests-on-failure -test-iterations 2 \
    -test-timeouts-enabled YES -collect-test-diagnostics never \
    -default-test-execution-time-allowance 300 -maximum-test-execution-time-allowance 600 \
    -resultBundlePath "build/RecipeClipperUITests$suffix.xcresult" 2>&1 | tee "build/ui-tests$suffix.log"
  return "${PIPESTATUS[0]}"
}

mkdir -p build
rm -rf build/RecipeClipperUITests.xcresult build/RecipeClipperUITests-rerun.xcresult \
  build/ui-tests.log build/ui-tests-rerun.log

run_tests "" "$@"
status=$?
logs=(build/ui-tests.log)
if [ "$status" -ne 0 ] && grep -q "never finished bootstrapping" build/ui-tests.log; then
  echo "The test runner never started: rebooting the simulator and running once more."
  xcrun simctl shutdown "$udid"
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b
  run_tests "-rerun" "$@"
  status=$?
  logs+=(build/ui-tests-rerun.log)
fi

report "${logs[@]}"
exit "$status"
