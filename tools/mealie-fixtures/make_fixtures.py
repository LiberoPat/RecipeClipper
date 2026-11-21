#!/usr/bin/env python3
"""Builds shared/fixtures/mealie/* (#391).

Mealie's bulk export (Data Management > Recipes > Export) is a zip of `recipes/<slug>/<slug>.json`
with no pictures; a single recipe's Download is a zip with one `.json` and one `.webp`; the API
gives a bare `.json`. The field names follow Mealie's schema (schema/recipe/recipe.py and
recipe_ingredient.py): `recipe_ingredient` entries with `original_text`, `display`, `note`,
`food`, `unit`, `title`, `reference_id`; `recipe_instructions` with `title` and `text`;
`recipe_yield`, `recipe_servings`, `prep_time`, `perform_time`, `total_time`, `notes`
(`title`, `text`), `org_url`, `recipe_category` and `tags` (`name`). The files here are written by
this script, not exported from Mealie: the recipes are made up. The picture is a small lossless
WebP the script encodes itself (no imaging library needed).
Run: python3 -I tools/mealie-fixtures/make_fixtures.py <output dir>
"""
import json
import struct
import sys
import zipfile
import zlib

OUT = sys.argv[1]
DATE = (2024, 1, 1, 0, 0, 0)


# --- a small WebP (VP8L, every symbol an 8-bit code), so the fixtures hold a real picture ---

class Bits:
    def __init__(self):
        self.acc = 0
        self.n = 0

    def put(self, value, count):
        """`count` bits of `value`, least significant first."""
        self.acc |= value << self.n
        self.n += count

    def code(self, value, count):
        """A prefix code, written most significant bit first."""
        for i in range(count - 1, -1, -1):
            self.put((value >> i) & 1, 1)

    def bytes(self):
        return self.acc.to_bytes((self.n + 7) // 8, "little")


def webp(width, height, pixel):
    """A lossless WebP of `pixel(x, y) -> (r, g, b)`."""
    bits = Bits()
    bits.put(0x2F, 8)
    bits.put(width - 1, 14)
    bits.put(height - 1, 14)
    bits.put(0, 1)  # alpha is not used
    bits.put(0, 3)  # version
    bits.put(0, 1)  # no transform
    bits.put(0, 1)  # no color cache
    bits.put(0, 1)  # no meta prefix codes

    # green (with length and cache symbols), red, blue, alpha: 8 bits each.
    for symbols in (280, 256, 256, 256):
        bits.put(0, 1)
        bits.put(12 - 4, 4)
        for symbol in (17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8):
            bits.put(1 if symbol in (0, 8) else 0, 3)
        bits.put(0, 1)
        for index in range(symbols):
            # Canonical code over {0, 8}, one bit each: 0 -> '0', 8 -> '1'.
            bits.put(1 if index < 256 else 0, 1)
    # distance: a simple code with one symbol, which is never used.
    bits.put(1, 1)
    bits.put(0, 1)
    bits.put(0, 1)
    bits.put(0, 1)
    for y in range(height):
        for x in range(width):
            r, g, b = pixel(x, y)
            bits.code(g, 8)
            bits.code(r, 8)
            bits.code(b, 8)
            bits.code(255, 8)
    data = bits.bytes()
    chunk = b"VP8L" + struct.pack("<I", len(data)) + data + (b"\x00" if len(data) % 2 else b"")
    return b"RIFF" + struct.pack("<I", 4 + len(chunk)) + b"WEBP" + chunk


def plate(x, y):
    """A paprika-to-cream gradient with a pale plate in the middle."""
    dx, dy = x - 64, y - 48
    if dx * dx + dy * dy < 30 * 30:
        return (251, 249, 246) if dx * dx + dy * dy > 24 * 24 else (191, 74, 43)
    t = y / 95
    return (int(191 + 40 * t), int(74 + 120 * t), int(43 + 160 * t))


PICTURE = webp(128, 96, plate)

# --- recipes ---


def ingredient(original, *, food=None, unit=None, quantity=None, note="", title=None, display=None, ref="r"):
    return {
        "quantity": quantity, "unit": unit, "food": food, "note": note, "is_food": food is not None,
        "disable_amount": food is None, "display": original if display is None else display,
        "title": title, "original_text": original, "reference_id": f"{ref}-{zlib.crc32(original.encode()) % 10**8:08d}",
    }


def step(text, title=""):
    return {"id": "s", "title": title, "summary": "", "text": text, "ingredient_references": []}


def recipe(slug, name, **fields):
    base = {
        "id": f"00000000-0000-4000-8000-{zlib.crc32(slug.encode()):012d}", "user_id": "u", "household_id": "h", "group_id": "g",
        "name": name, "slug": slug, "image": "aBcD", "recipe_servings": 0.0, "recipe_yield_quantity": 0.0,
        "recipe_yield": None, "total_time": None, "prep_time": None, "cook_time": None, "perform_time": None,
        "description": "A made-up recipe.", "recipe_category": [], "tags": [], "tools": [], "rating": None,
        "org_url": None, "date_added": "2025-01-01", "recipe_ingredient": [], "recipe_instructions": [],
        "nutrition": {}, "settings": {"public": True}, "assets": [], "notes": [], "extras": {}, "comments": [],
    }
    base.update(fields)
    return json.dumps(base, ensure_ascii=False, indent=2).encode("utf-8")


def named(name):
    return {"id": "x", "name": name, "slug": name.lower()}


pasta = recipe(
    "pasta-e-ceci", "Pasta e Ceci", recipe_servings=4.0, recipe_yield="4 servings", total_time="45 minutes",
    prep_time="10 minutes", perform_time="35 minutes", org_url="http://www.Example.com/pasta-e-ceci?utm_source=mealie&id=9#top",
    recipe_category=[named("Dinner"), named("Soup")], tags=[named("Vegetarian"), named("dinner")],
    recipe_ingredient=[
        ingredient("2 cups canned chickpeas, drained", food={"name": "chickpeas"}, unit={"name": "cup"}, quantity=2.0, title="The base"),
        ingredient("1 tbsp olive oil", food={"name": "olive oil"}, unit={"name": "tablespoon"}, quantity=1.0),
        ingredient("200 g ditalini", food={"name": "ditalini"}, unit={"name": "gram"}, quantity=200.0, title="The pasta"),
        ingredient("salt", food={"name": "salt"}),
    ],
    recipe_instructions=[
        step("Heat the oil and soften the garlic.", "Start the soup"),
        step("Add the chickpeas and 4 cups of water, then simmer for 20 minutes."),
        step("", "Finish"),
        step("Stir in the pasta and cook until tender."),
    ],
    notes=[{"title": "Storage", "text": "Keeps 3 days in the fridge."}, {"title": "", "text": "Thickens as it stands."}],
)
# Before Mealie 3.1 a line that was never parsed was stored with quantity 1 and its text in the note.
old_soup = recipe(
    "old-lentil-soup", "Old Lentil Soup", recipe_yield=None, recipe_servings=2.5, total_time="PT1H15M",
    prep_time=None, prep_time_seconds=600,
    recipe_ingredient=[
        {"quantity": 1.0, "unit": None, "food": None, "note": "2 cups red lentils", "is_food": False, "disable_amount": True,
         "display": "1 2 cups red lentils", "title": None, "original_text": None, "reference_id": "old-1"},
        {"quantity": 1.0, "unit": None, "food": None, "note": "1 onion, chopped", "is_food": False, "disable_amount": True,
         "display": "1 1 onion, chopped", "title": None, "original_text": "", "reference_id": "old-2"},
        {"quantity": 2.0, "unit": {"name": "cup"}, "food": {"name": "water"}, "note": "", "is_food": True, "disable_amount": False,
         "display": "2 cups water", "title": None, "original_text": None, "reference_id": "old-3"},
    ],
    recipe_instructions=[step("Simmer everything for 25 minutes."), step("Blend.")],
)
duplicate = recipe(
    "pasta-e-ceci-2", "Pasta e Ceci (again)", org_url="https://www.example.com/pasta-e-ceci?id=9&utm_medium=x",
    recipe_category=[named("Weeknight")],
    recipe_ingredient=[ingredient("1 can chickpeas", food={"name": "chickpeas"})], recipe_instructions=[step("Cook.")],
)
nameless = recipe(
    "no-name", "", recipe_ingredient=[ingredient("1 egg", food={"name": "egg"})], recipe_instructions=[step("Boil it.")],
)
empty = recipe("just-a-title", "Just A Title")
schema_org = json.dumps({
    "@context": "https://schema.org", "@type": "Recipe", "name": "Schema Soup", "recipeIngredient": ["1 cup water"],
    "recipeInstructions": [{"@type": "HowToStep", "text": "Boil."}], "slug": "schema-soup",
}).encode()
camel_tea = json.dumps({
    "id": "11111111-2222-4333-8444-555555555555", "name": "Mint Tea", "slug": "mint-tea", "recipeServings": 1.0,
    "recipeYield": "", "prepTime": "", "performTime": "5 minutes", "totalTime": "PT10M", "orgURL": None,
    "recipeCategory": [{"id": "c", "name": "Drinks", "slug": "drinks"}], "tags": [{"id": "t", "name": "Quick", "slug": "quick"}],
    "recipeIngredient": [
        {"quantity": 1.0, "unit": None, "food": {"name": "mint"}, "note": "a handful", "display": "1 handful mint",
         "title": None, "originalText": "a handful of fresh mint", "referenceId": "t-1"},
    ],
    "recipeInstructions": [{"id": "s", "title": None, "text": "Steep the mint in hot water for 5 minutes."}],
    "notes": [], "description": "Not kept.",
}, ensure_ascii=False, indent=2).encode()

JUNK = [
    ("__MACOSX/recipes/pasta-e-ceci/._pasta-e-ceci.json", b"\x00\x05\x16\x07 AppleDouble"),
    ("recipes/.DS_Store.json", b"hidden"),
    ("readme.txt", b"hello"),
    ("recipes/", b""),
]


def write_zip(name, entries):
    with zipfile.ZipFile(f"{OUT}/{name}", "w", zipfile.ZIP_DEFLATED) as z:
        for entry, data in entries:
            info = zipfile.ZipInfo(entry, DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


write_zip("mealie-bulk.zip", [
    ("recipes/pasta-e-ceci/pasta-e-ceci.json", pasta),
    ("recipes/old-lentil-soup/old-lentil-soup.json", old_soup),
    ("recipes/mint-tea/mint-tea.json", camel_tea),
    ("recipes/pasta-e-ceci-2/pasta-e-ceci-2.json", duplicate),
    ("recipes/no-name/no-name.json", nameless),
    ("recipes/just-a-title/just-a-title.json", empty),
    ("recipes/schema-soup/schema-soup.json", schema_org),
    ("recipes/broken/broken.json", b"this is not json"),
] + JUNK)

# One recipe's Download: its .json and its .webp.
write_zip("mealie-recipe-download.zip", [("pasta-e-ceci.json", pasta), ("original.webp", PICTURE)])

# The same shape with the picture where the bulk export would put it, under the recipe's own folder.
write_zip("mealie-with-pictures.zip", [
    ("recipes/pasta-e-ceci/pasta-e-ceci.json", pasta),
    ("recipes/pasta-e-ceci/images/original.webp", PICTURE),
    ("recipes/mint-tea/mint-tea.json", camel_tea),
])

with open(f"{OUT}/mealie-recipe.json", "wb") as f:
    f.write(camel_tea)
with open(f"{OUT}/mealie-pre31.json", "wb") as f:
    f.write(old_soup)
with open(f"{OUT}/mealie-schema-org.json", "wb") as f:
    f.write(schema_org)

# Only a schema.org file inside a zip: not a Mealie export.
write_zip("mealie-only-schema.zip", [("recipes/schema-soup/schema-soup.json", schema_org)])
write_zip("mealie-not-mealie.zip", [("readme.txt", b"hello"), ("photo.webp", PICTURE)])

# Cut off in the middle: no end-of-central-directory record.
with open(f"{OUT}/mealie-bulk.zip", "rb") as f:
    data = f.read()
with open(f"{OUT}/mealie-truncated.zip", "wb") as f:
    f.write(data[: len(data) * 6 // 10])

# One good recipe among files that are not UTF-8, not JSON, or JSON that is not an object.
write_zip("mealie-bad-entries.zip", [
    ("recipes/a/a.json", b'{"name": "\xff\xfe", "slug": "a"}'),
    ("recipes/b/b.json", b"this is not json"),
    ("recipes/c/c.json", b'[{"name": "In a list", "slug": "c"}]'),
    ("recipes/d/d.json", camel_tea),
])

# An entry whose zip headers say it is 4 GB when unpacked, beside a good one.
write_zip("mealie-huge-declared.zip", [("recipes/liar/liar.json", pasta), ("recipes/d/d.json", camel_tea)])
with open(f"{OUT}/mealie-huge-declared.zip", "rb") as f:
    b = bytearray(f.read())
local = b.find(b"PK\x03\x04")
struct.pack_into("<I", b, local + 22, 0xFFFFFFF0)
central = b.find(b"PK\x01\x02")
struct.pack_into("<I", b, central + 24, 0xFFFFFFF0)
with open(f"{OUT}/mealie-huge-declared.zip", "wb") as f:
    f.write(b)

# A small file that unpacks to 40 MB of spaces (over the 32 MiB an entry may hold), beside a good one.
write_zip("mealie-bomb.zip", [("recipes/bomb/bomb.json", b" " * (40 * 1024 * 1024)), ("recipes/d/d.json", camel_tea)])
# ... whose headers lie the other way: they say the entry is 100 bytes, so only counting what really unpacks catches it.
with open(f"{OUT}/mealie-bomb.zip", "rb") as f:
    b = bytearray(f.read())
struct.pack_into("<I", b, b.find(b"PK\x03\x04") + 22, 100)
struct.pack_into("<I", b, b.find(b"PK\x01\x02") + 24, 100)
with open(f"{OUT}/mealie-bomb.zip", "wb") as f:
    f.write(b)

# Pictures: a good one, one that is not an image, one over the 8 MiB a picture may be, a stray one that
# belongs to no recipe, and a recipe with none.
write_zip("mealie-photos.zip", [
    ("recipes/a/a.json", recipe("a", "Toast", recipe_ingredient=[ingredient("1 slice bread", food={"name": "bread"})])),
    ("recipes/a/images/original.webp", PICTURE),
    ("recipes/b/b.json", recipe("b", "Jam", recipe_ingredient=[ingredient("1 jar jam", food={"name": "jam"})])),
    ("recipes/b/images/original.webp", b"bad picture, not an image"),
    ("recipes/c/c.json", recipe("c", "Honey", recipe_ingredient=[ingredient("1 spoon honey", food={"name": "honey"})])),
    ("recipes/c/images/original.webp", b"RIFF\x00\x00\x00\x00WEBP" + b"\x00" * (9 * 1024 * 1024)),
    ("recipes/d/d.json", recipe("d", "Salt", recipe_ingredient=[ingredient("1 pinch salt", food={"name": "salt"})])),
    ("stray/stray.webp", PICTURE),
])
