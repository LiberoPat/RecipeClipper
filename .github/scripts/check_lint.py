"""Fail if Android lint found anything besides the known version advisories.

Lint reports 16 warnings on purpose, all version advisories (CLAUDE.md, #23).
Their number can drift as new library versions come out, so this checks the
issue ids, not the count. Any other finding, at any severity, is real.
"""
import sys
import xml.etree.ElementTree as ET

ALLOWED = {
    "AndroidGradlePluginVersion",
    "GradleDependency",
    "NewerVersionAvailable",
    "OldTargetApi",
}

issues = ET.parse(sys.argv[1]).getroot().findall("issue")
unexpected = [i for i in issues if i.get("id") not in ALLOWED]
print(f"{len(issues)} lint findings, {len(unexpected)} not a known version advisory")
for issue in unexpected:
    loc = issue.find("location")
    where = f'{loc.get("file")}:{loc.get("line")}' if loc is not None else "?"
    print(f'::error::{issue.get("severity")} {issue.get("id")} at {where}: {issue.get("message")}')
sys.exit(1 if unexpected else 0)
