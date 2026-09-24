# -*- coding: utf-8 -*-
"""Generates values-{es,fr,de,it,pt-rBR}/strings.xml and ios/.../Localizable.xcstrings from tr.py.

Usage: python3 gen.py <repo root>
Checks: every translatable Android base string has a row (and the English matches), every
row has every language, and placeholders agree between English and each translation.
"""
import json, os, re, sys, xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
from tr import ROWS, LANGS  # noqa: E402

root = sys.argv[1]
base = os.path.join(root, "app/src/main/res/values/strings.xml")
ANDROID_DIR = dict(es="values-es", fr="values-fr", de="values-de", it="values-it", pt="values-pt-rBR")
IOS_LANG = dict(en="en", es="es", fr="fr", de="de", it="it", pt="pt-BR")
CATALOG = os.path.join(root, "ios/RecipeClipper/Resources/Localizable.xcstrings")

rows = {r["name"]: r for r in ROWS}
errors = []

# ---- Android base: order, translatability, English check
tree = ET.parse(base)
order = []
for el in tree.getroot():
    name = el.get("name")
    if el.get("translatable") == "false":
        continue
    order.append(name)
    if name not in rows:
        errors.append(f"no translation row for Android string {name}")
        continue
    r = rows[name]
    if el.tag == "string":
        en = (el.text or "")
        en = en.replace("\\'", "'").replace('\\"', '"')
        if r["kind"] != "s" or en != r["v"]["en"]:
            errors.append(f"English mismatch for {name}: {en!r} vs {r['v']['en']!r}")
    else:
        items = {i.get("quantity"): i.text for i in el}
        if r["kind"] != "p" or items != r["v"]["en"]:
            errors.append(f"English plural mismatch for {name}: {items} vs {r['v']['en']}")

for name, r in rows.items():
    if name not in order and r["ios"] is None:
        errors.append(f"row {name} is used by neither platform")

PH = re.compile(r"%(\d\$)?[sd@]|%(\d\$)?lld")


def placeholders(s):
    return sorted(m.group(0).replace("lld", "d") for m in PH.finditer(s))


for name, r in rows.items():
    langs = ["en"] + LANGS
    if r["kind"] == "s":
        for l in langs:
            if not r["v"].get(l):
                errors.append(f"{name}: missing {l}")
            elif placeholders(r["v"][l]) != placeholders(r["v"]["en"]):
                errors.append(f"{name}: placeholders differ in {l}: {r['v'][l]!r}")
    else:
        for l in langs:
            forms = r["v"].get(l)
            if not forms or "one" not in forms or "other" not in forms:
                errors.append(f"{name}: plural forms missing in {l}")
                continue
            for q, t in forms.items():
                if placeholders(t) != placeholders(r["v"]["en"]["other"]):
                    errors.append(f"{name}: placeholders differ in {l}/{q}: {t!r}")

if errors:
    print("\n".join(errors))
    sys.exit(1)


# ---- Android output
def esc(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    if s[:1] in ("@", "?"):
        s = "\\" + s
    return s


for lang in LANGS:
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Generated from the translation table for issue #13; a native speaker should review.",
           "     See docs/translations.md. -->",
           "<resources>"]
    for name in order:
        r = rows[name]
        if r["kind"] == "s":
            v = r["android"].get(lang, r["v"][lang])
            out.append(f'    <string name="{name}">{esc(v)}</string>')
        else:
            out.append(f'    <plurals name="{name}">')
            for q in ("zero", "one", "two", "few", "many", "other"):
                if q in r["v"][lang]:
                    out.append(f'        <item quantity="{q}">{esc(r["v"][lang][q])}</item>')
            out.append("    </plurals>")
    out.append("</resources>")
    d = os.path.join(root, "app/src/main/res", ANDROID_DIR[lang])
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "strings.xml"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


# ---- iOS output
def ios_specs(en):
    """The specifiers Swift interpolation puts in the key, in argument order."""
    found = []
    for m in re.finditer(r"%(?:(\d)\$)?([sd])", en):
        idx = int(m.group(1)) if m.group(1) else len(found) + 1
        found.append((idx, "%@" if m.group(2) == "s" else "%lld"))
    return [s for _, s in sorted(set(found))]


def ios_value(v, multi):
    v = re.sub(r" {2,}", " ", v)
    if multi:
        v = re.sub(r"%(\d)\$s", r"%\1$@", v)
        v = re.sub(r"%(\d)\$d", r"%\1$lld", v)
    else:
        v = re.sub(r"%(?:1\$)?s", "%@", v)
        v = re.sub(r"%(?:1\$)?d", "%lld", v)
    return v


def unit(v):
    return {"stringUnit": {"state": "translated", "value": v}}


strings = {}
for name, r in rows.items():
    if not r["ios"]:
        continue
    en = r["v"]["en"] if r["kind"] == "s" else r["v"]["en"]["other"]
    specs = ios_specs(en)
    key = " ".join([r["ios"]] + specs)
    multi = len(specs) > 1
    locs = {}
    for l in ["en"] + LANGS:
        if r["kind"] == "s":
            v = r["iosv"].get(l, r["v"][l])
            locs[IOS_LANG[l]] = unit(ios_value(v, multi))
        else:
            locs[IOS_LANG[l]] = {"variations": {"plural": {
                q: unit(ios_value(t, multi)) for q, t in r["v"][l].items()}}}
    entry = {"extractionState": "manual", "localizations": dict(sorted(locs.items()))}
    if r.get("comment"):
        entry = {"comment": r["comment"], **entry}
    strings[key] = entry

catalog = {"sourceLanguage": "en", "strings": dict(sorted(strings.items())), "version": "1.0"}
os.makedirs(os.path.dirname(CATALOG), exist_ok=True)
with open(CATALOG, "w", encoding="utf-8") as f:
    f.write(json.dumps(catalog, ensure_ascii=False, indent=2, separators=(",", " : ")) + "\n")

print(f"android: {len(order)} names x {len(LANGS)} languages; ios: {len(strings)} keys")
for k in sorted(strings):
    print("  ios key:", k)
