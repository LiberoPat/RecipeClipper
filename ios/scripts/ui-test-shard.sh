#!/bin/bash
# Splits the iOS UI test classes into parallel shards for .github/workflows/ios-ui-tests.yml.
#
#   ios/scripts/ui-test-shard.sh <index> <count>        # 1-based; prints -only-testing: arguments
#   ios/scripts/ui-test-shard.sh --summary <count>      # every shard, its classes and its load
#   ios/scripts/ui-test-shard.sh --update-weights <xcodebuild-log>...
#
# Every class in ios/RecipeClipperUITests is found by reading the sources (a class that
# inherits XCTestCase or one of the project's own test base classes, with the tests that
# extensions such as WalkthroughUITests+Kitchen.swift add to it counted for that class).
# Classes are packed greedily by the seconds in ui-test-weights.json (heaviest first, into
# the lightest shard; ties by name and shard number, so a run is deterministic). A class with
# no weight counts as the median. The script exits non-zero if a class would run in no shard or
# in two, or if a `func test...` in the folder isn't claimed by a class it found.
#
# --update-weights reads the "Test Case ... passed (N seconds)" lines of full-run logs
# (`gh run view <id> --log > run.log`), sums them per class (a test that ran again after a failure
# counts its last run only), averages across the logs given and rewrites the weights file. See
# docs/testing.md.

exec python3 -I - "$0" "$@" <<'PY'
import collections
import json
import os
import re
import statistics
import sys

SCRIPT = os.path.abspath(sys.argv[1])
ARGS = sys.argv[2:]
UITESTS = os.path.join(os.path.dirname(SCRIPT), "..", "RecipeClipperUITests")
WEIGHTS = os.path.join(UITESTS, "ui-test-weights.json")
TARGET = "RecipeClipperUITests"


def die(message):
    sys.stderr.write("ui-test-shard: " + message + "\n")
    sys.exit(1)


# A column-0 declaration opens a type; a `func test...` belongs to the latest one.
DECLARATION = re.compile(r"^(?:(?:final|open|public|internal|private|fileprivate)\s+)*"
                         r"(class|struct|enum|extension|protocol|actor)\s+(\w+)\s*(?::\s*([\w., ]+))?")
TEST_FUNC = re.compile(r"^\s*(?:@\w+\s+)*(?:(?:public|open|internal|private|fileprivate|final)\s+)*func\s+(test\w*)\s*\(")


def discover():
    superclass = {}              # class name -> first inherited name
    tests = collections.Counter()  # type name -> number of test functions (extensions included)
    kinds = {}                   # type name -> declaration keyword
    files = []
    for root, _, names in os.walk(UITESTS):
        files.extend(os.path.join(root, n) for n in names if n.endswith(".swift"))
    total_funcs = 0
    for path in sorted(files):
        current = None
        for line in open(path, encoding="utf-8"):
            m = DECLARATION.match(line)
            if m:
                keyword, name, inherits = m.group(1), m.group(2), m.group(3)
                current = name
                if keyword != "extension":
                    kinds[name] = keyword
                    if keyword == "class" and inherits:
                        superclass[name] = inherits.split(",")[0].strip()
                continue
            if TEST_FUNC.match(line):
                total_funcs += 1
                if current is None:
                    die("%s: a test function outside any type: %s" % (path, line.strip()))
                tests[current] += 1
    # A class is a test class when XCTestCase is among its ancestors.
    def is_test_class(name, seen=()):
        parent = superclass.get(name)
        if parent == "XCTestCase":
            return True
        if parent is None or parent in seen:
            return False
        return is_test_class(parent, seen + (name,))
    test_classes = {n for n in kinds if kinds[n] == "class" and is_test_class(n)}
    # A base class has no tests of its own to run; its subclasses run the inherited ones.
    bases = {superclass[n] for n in test_classes if superclass.get(n) in test_classes}
    runnable = {n for n in test_classes if n not in bases}
    for name in bases:
        if tests[name]:
            die("%s is a base class with test functions; subclasses would each run them" % name)
    claimed = sum(tests[n] for n in runnable)
    if claimed != total_funcs:
        orphans = sorted(n for n in tests if n not in runnable and tests[n])
        die("%d of %d test functions belong to types this script doesn't treat as test classes: %s"
            % (total_funcs - claimed, total_funcs, ", ".join(orphans) or "?"))
    empty = sorted(n for n in runnable if not tests[n])
    for name in empty:
        sys.stderr.write("ui-test-shard: note: %s has no test functions, not assigned\n" % name)
    return sorted(n for n in runnable if tests[n])


def load_weights():
    try:
        with open(WEIGHTS, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def pack(classes, count):
    weights = load_weights()
    known = [weights[c] for c in classes if c in weights]
    median = statistics.median(known) if known else 1
    weight = {c: float(weights.get(c, median)) for c in classes}
    for stale in sorted(set(weights) - set(classes)):
        sys.stderr.write("ui-test-shard: note: %s is in the weights file but is not a test class\n" % stale)
    for missing in sorted(set(classes) - set(weights)):
        sys.stderr.write("ui-test-shard: note: %s has no weight, using the median (%gs)\n" % (missing, median))
    shards = [[] for _ in range(count)]
    load = [0.0] * count
    for c in sorted(classes, key=lambda c: (-weight[c], c)):
        lightest = min(range(count), key=lambda i: (load[i], i))
        shards[lightest].append(c)
        load[lightest] += weight[c]
    # Every class in exactly one shard, or no test run at all.
    seen = collections.Counter(c for s in shards for c in s)
    wrong = [c for c in classes if seen[c] != 1]
    if wrong or sum(seen.values()) != len(classes):
        die("not every class is in exactly one shard: " + ", ".join(wrong))
    return shards, load


def update_weights(logs):
    pattern = re.compile(r"Test Case '-\[%s\.(\w+) (\w+)\]' (?:passed|failed|skipped) \(([\d.]+) seconds\)" % TARGET)
    per_log = []
    for path in logs:
        # Per test, its last run: a retried test (-retry-tests-on-failure) logs one line a try.
        last = {}
        for line in open(path, encoding="utf-8", errors="replace"):
            m = pattern.search(line)
            if m:
                last[(m.group(1), m.group(2))] = float(m.group(3))
        sums = collections.defaultdict(float)
        for (cls, _), seconds in last.items():
            sums[cls] += seconds
        if not sums:
            die("%s has no 'Test Case ... (N seconds)' lines" % path)
        per_log.append(sums)
    classes = discover()
    result = {}
    for c in classes:
        values = [s[c] for s in per_log if c in s]
        if values:
            result[c] = max(1, round(sum(values) / len(values)))
        else:
            sys.stderr.write("ui-test-shard: note: %s is in no log, left out (it will count as the median)\n" % c)
    with open(WEIGHTS, "w", encoding="utf-8") as f:
        json.dump(dict(sorted(result.items())), f, indent=2)
        f.write("\n")
    print("wrote %d classes to %s" % (len(result), os.path.normpath(WEIGHTS)))


def main():
    if not ARGS:
        die("usage: ui-test-shard.sh <index> <count> | --summary <count> | --update-weights <log>...")
    if ARGS[0] == "--update-weights":
        if len(ARGS) < 2:
            die("--update-weights needs at least one log")
        update_weights(ARGS[1:])
        return
    if ARGS[0] == "--summary":
        count = int(ARGS[1])
        shards, load = pack(discover(), count)
        for i, s in enumerate(shards):
            print("shard %d/%d: %d classes, %.0fs (%.1f min)" % (i + 1, count, len(s), load[i], load[i] / 60))
            for c in s:
                print("  " + c)
        return
    if len(ARGS) != 2:
        die("usage: ui-test-shard.sh <index> <count>")
    index, count = int(ARGS[0]), int(ARGS[1])
    if count < 1 or not 1 <= index <= count:
        die("index must be 1..count")
    shards, load = pack(discover(), count)
    sys.stderr.write("ui-test-shard: shard %d/%d: %d classes, about %.0fs\n"
                     % (index, count, len(shards[index - 1]), load[index - 1]))
    for c in shards[index - 1]:
        print("-only-testing:%s/%s" % (TARGET, c))


main()
PY
