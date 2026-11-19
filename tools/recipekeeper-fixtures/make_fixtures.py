#!/usr/bin/env python3
"""Builds shared/fixtures/recipekeeper/*.zip (#390).

A Recipe Keeper export is a zip of `recipes.html` (sometimes inside one wrapper folder) and an
`images/` folder of `<recipeId>_<N>.jpg`. Recipe Keeper publishes no format, so these files follow
what open-source importers (RecipeSage, Mealie, Norish's spec of a real 488-recipe export) document:
one `div.recipe-details` per recipe, values read by `itemprop`, translated visible labels, unclosed
`<meta>`, unquoted attribute values (`itemprop=photo0/`), bold ingredient headings, numbered
directions with empty paragraphs between. The recipes here are made up; no real export is used.

Run: python3 -I tools/recipekeeper-fixtures/make_fixtures.py <output dir>
"""
import io
import struct
import sys
import zipfile

OUT = sys.argv[1]
DATE = (2026, 1, 1, 0, 0, 0)

GOOD = b"\xff\xd8\xff\xe0\x00\x10JFIF-not-a-real-picture"
BAD = b"bad picture, not an image"

HEAD = (
    "<!DOCTYPE html><html><head><style type=text/css>html{}body{font-family:sans-serif}"
    ".recipe-details h2{color:#d24400}</style></head><body>"
)
TAIL = "</body></html>"


def p(*lines):
    return "".join(f"<p>{line}</p>" for line in lines)


def recipe(
    rid="", name="", course=(), category=(), collection=(), source="Recipe Keeper", yield_="",
    prep="PT0S", cook="PT0S", ingredients=(), directions=(), notes=(), photos=(), favourite=False,
    labels=("Courses", "Categories", "Collections", "Source", "Serving size", "Preparation time", "Cooking time"),
):
    """One div.recipe-details written the way Recipe Keeper writes it: attributes unquoted unless they hold a space."""
    lc, lcat, lcol, lsrc, lyield, lprep, lcook = labels
    out = ["<div class=recipe-details>"]
    if rid:
        out.append(f"<meta content={rid} itemprop=recipeId>")
    out.append('<meta content="" itemprop=recipeShareId>')
    out.append(f"<meta content={'True' if favourite else 'False'} itemprop=recipeIsFavourite>")
    out.append("<meta content=0 itemprop=recipeRating>")
    out.append("<table><tr><td>")
    if photos:
        out.append(f'<img src={photos[0]} class="recipe-photo"/>')
    out.append(f"</td><td style=vertical-align:top><h2 itemprop=name>{name}</h2>")
    # The first course is a span with its text, every further one a meta.
    if course:
        extra = "".join(f'<meta content="{c}" itemprop=recipeCourse>' for c in course[1:])
        out.append(f"<div>{lc}: <span itemprop=recipeCourse>{course[0]}</span>{extra}</div>")
    else:
        out.append(f"<div>{lc}: <span></span></div>")
    out.append(f"<div>{lcat}: <span>{', '.join(category)}</span>" + "".join(f'<meta content="{c}" itemprop=recipeCategory>' for c in category) + "</div>")
    out.append(f"<div>{lcol}: <span>{', '.join(collection)}</span>" + "".join(f'<meta content="{c}" itemprop=recipeCollection>' for c in collection) + "</div>")
    out.append(f"<div>{lsrc}: <span itemprop=recipeSource>{source}</span></div>")
    out.append(f"<div>{lyield}: <span itemprop=recipeYield>{yield_}</span></div>")
    out.append(f"<div>{lprep}: <span>x</span><meta content={prep} itemprop=prepTime></div>")
    out.append(f"<div>{lcook}: <span>x</span><meta content={cook} itemprop=cookTime></div>")
    out.append("</td></tr><tr><td style=vertical-align:top;width:250px><h3>Ingredients</h3>")
    out.append(f"<div class=recipe-ingredients itemprop=recipeIngredients>{p(*ingredients)}</div>")
    out.append("</td><td style=vertical-align:top><h3>Directions</h3>")
    out.append(f"<div itemprop=recipeDirections>{p(*directions)}</div></td></tr></table>")
    out.append(f"<div class=recipe-notes itemprop=recipeNotes>{p(*notes)}</div>")
    out.append("<h3>Nutrition</h3><div>Calories: 571<meta content=571 itemprop=recipeNutCalories></div>")
    if photos:
        out.append("<h3>Photos</h3>")
        for i, src in enumerate(photos):
            out.append(f"<div class=recipe-photos-div><img src={src} class=recipe-photos itemprop=photo{i}/></div>")
    out.append("<hr/></div>")
    return "".join(out)


def page(*recipes):
    return (HEAD + "".join(recipes) + TAIL).encode("utf-8")


def write_zip(name, entries):
    with zipfile.ZipFile(f"{OUT}/{name}", "w", zipfile.ZIP_DEFLATED) as z:
        for entry, data in entries:
            info = zipfile.ZipInfo(entry, DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


def patch_declared_size(name, entry, size):
    """Sets the uncompressed size a zip's headers claim for `entry`, in the local and the central header."""
    path = f"{OUT}/{name}"
    b = bytearray(open(path, "rb").read())
    needle = entry.encode()
    at = 0
    while True:
        at = b.find(b"PK\x01\x02", at)
        assert at >= 0, entry
        name_len = struct.unpack_from("<H", b, at + 28)[0]
        if bytes(b[at + 46: at + 46 + name_len]) == needle:
            struct.pack_into("<I", b, at + 24, size)
            local = struct.unpack_from("<I", b, at + 42)[0]
            struct.pack_into("<I", b, local + 22, size)
            break
        at += 4
    open(path, "wb").write(b)


# ---- The valid export: root layout. ----
cheesecake = recipe(
    rid="a2489b23-050f-5c8a-8e5d-12fdb9fdeea3", name="Blueberry Cheesecake", course=["Dessert"], category=["Cake"],
    collection=["Holiday Baking"], yield_="Serves 6-8", prep="PT10M", cook="PT1H",
    ingredients=["<b>For the base</b>", "1 1/2 cups digestive biscuit crumbs", "1/2 cup melted butter", "",
                 "<b><i>For the filling:</i></b>", "600 g cream cheese", "2 large eggs", ""],
    directions=["1. Melt the butter and mix it into the crumbs. Press into a 9-inch pan. Bake at 325&#176;F for 10 minutes.", "",
                "2. Beat the cream cheese with the eggs until smooth.", "", "3. Pour over the base and bake for 40 minutes.", ""],
    notes=["Better the next day.", "Freezes well."],
    photos=["images/a2489b23-050f-5c8a-8e5d-12fdb9fdeea3_0.jpg", "images/a2489b23-050f-5c8a-8e5d-12fdb9fdeea3_1.jpg"],
)
cookies = recipe(
    rid="b0bcddc4-23e8-50cc-a879-22b1b2e63919", name="Chocolate Chip Cookies", course=["Snack", "Dessert"],
    category=["Baking", "Cookie"], source="http://www.Example.com/cookies?utm_source=rk&amp;id=3#top", yield_="12",
    prep="PT5M", cook="PT0S", favourite=True,
    ingredients=["2 1/4 cups all-purpose flour", "1 cup butter", "2 eggs", ""],
    directions=["1. Combine everything.", "", "2. Bake at 375F for 10 minutes.", ""],
    photos=["images/b0bcddc4-23e8-50cc-a879-22b1b2e63919_0.jpg"],
)
tiramisu = recipe(
    rid="c3d4e5f6-0000-4000-8000-000000000003", name="Tiramisù", course=["Dolce"], category=["Dolci"],
    collection=["Dolci della nonna"], yield_="4 persone", prep="PT30M", cook="PT0S",
    labels=("Portate", "Categorie", "Raccolte", "Fonte", "Porzioni", "Tempo di preparazione", "Tempo di cottura"),
    ingredients=["Per la crema:", "3 uova", "250 g mascarpone", "<b>Per la base</b>", "savoiardi q.b."],
    directions=["1. Montare i tuorli con lo zucchero.", "2. Aggiungere il mascarpone."],
    photos=["images/c3d4e5f6-0000-4000-8000-000000000003_0.jpg", "images/c3d4e5f6-0000-4000-8000-000000000003_1.jpg"],
)
pasta = recipe(
    rid="d4e5f6a7-0000-4000-8000-000000000004", name="Pasta al Pomodoro", source="n/a",
    ingredients=["400 g spaghetti", "1 can tomatoes"],
    directions=["Cook the pasta.", "2 minutes before the end, add the basil.", "12. Serve."],
    photos=["images/missing.jpg"],
)
no_name = recipe(rid="e5f6a7b8-0000-4000-8000-000000000005", name="", ingredients=["1 egg"], directions=["Boil it."])
no_content = recipe(rid="f6a7b8c9-0000-4000-8000-000000000006", name="Just A Title")
cheesecake_again = recipe(
    rid="a2489b23-050f-5c8a-8e5d-12fdb9fdeea3", name="Blueberry Cheesecake (copy)", category=["Party"],
    ingredients=["1 egg"], directions=["Mix."],
)
mint_tea = recipe(name="Mint Tea", category=["Drinks"], directions=["Steep mint leaves in hot water for 5 minutes."])
mac = recipe(
    rid="a7b8c9d0-0000-4000-8000-000000000007", name="Mac &amp; Cheese &#8211; Quick",
    ingredients=["300 g macaroni", "200 g cheddar"], directions=["1. Boil. Stir in the cheese."],
    photos=["images/mac%20and%20cheese.jpg"],
)

write_zip("recipekeeper-valid.zip", [
    ("recipes.html", page(cheesecake, cookies, tiramisu, pasta, no_name, no_content, cheesecake_again, mint_tea, mac)),
    ("images/a2489b23-050f-5c8a-8e5d-12fdb9fdeea3_0.jpg", GOOD),
    ("images/a2489b23-050f-5c8a-8e5d-12fdb9fdeea3_1.jpg", GOOD + b"2"),
    ("images/b0bcddc4-23e8-50cc-a879-22b1b2e63919_0.jpg", GOOD + b"3"),
    ("images/c3d4e5f6-0000-4000-8000-000000000003_0.jpg", BAD),
    ("images/c3d4e5f6-0000-4000-8000-000000000003_1.jpg", GOOD + b"4"),
    ("images/mac and cheese.jpg", GOOD + b"5"),
    ("images/", b""),
])

# ---- The wrapper folder, a BOM, macOS junk, and addresses that must never be followed. ----
wrap = "RecipeKeeper_20261119_101500"
soup = recipe(
    rid="11111111-2222-3333-4444-555555555555", name="Gran's Soup", ingredients=["1 onion", "4 cups water"],
    directions=["1. Simmer."], photos=["images/soup_0.jpg"],
)
sneaky = recipe(
    rid="22222222-2222-3333-4444-555555555555", name="Flapjacks", ingredients=["2 cups oats"], directions=["Bake."],
    photos=["../../evil.jpg", "https://evil.example/x.jpg", "/etc/passwd", "C:\\x.jpg", "file:///etc/hosts"],
)
old = recipe(rid="33333333-2222-3333-4444-555555555555", name="Old Recipe", ingredients=["1 egg"], directions=["Boil."])
write_zip("recipekeeper-wrapper.zip", [
    (f"__MACOSX/{wrap}/._recipes.html", b"\x00\x05\x16\x07 AppleDouble"),
    (f"{wrap}/", b""),
    (f"{wrap}/old/recipes.html", page(old)),
    (f"{wrap}/recipes.html", b"\xef\xbb\xbf" + page(soup, sneaky)),
    (f"{wrap}/images/soup_0.jpg", GOOD),
    ("../evil.txt", b"never written"),
])

# ---- Not Recipe Keeper's: a web page with no recipe blocks, and a zip with no page. ----
write_zip("recipekeeper-not.zip", [
    ("recipes.html", b"<html><body><h1>My recipes</h1><p>Nothing here.</p></body></html>"),
    ("readme.txt", b"hello"),
])
write_zip("recipekeeper-no-page.zip", [("notes.txt", b"hello"), ("images/a.jpg", GOOD)])

# ---- Cut off in the middle: no end-of-central-directory record. ----
data = open(f"{OUT}/recipekeeper-valid.zip", "rb").read()
open(f"{OUT}/recipekeeper-truncated.zip", "wb").write(data[: len(data) * 6 // 10])

# ---- Hostile sizes. ----
# A page that unpacks to 40 MB (over the 32 MiB a page may hold).
write_zip("recipekeeper-bomb.zip", [("recipes.html", b"<html><body>" + b"<p>" * (40 * 1024 * 1024 // 3))])
# A page whose headers say 4 GB when unpacked.
write_zip("recipekeeper-huge-declared.zip", [("recipes.html", page(soup))])
patch_declared_size("recipekeeper-huge-declared.zip", "recipes.html", 0xFFFFFFF0)
# Pictures: one that unpacks to 9 MB (over the 8 MiB a picture may be), one whose header says 4 GB, one good.
photos = [
    recipe(rid="aaaaaaaa-0000-4000-8000-000000000001", name="Toast", ingredients=["1 slice bread"], photos=["images/good.jpg"]),
    recipe(rid="aaaaaaaa-0000-4000-8000-000000000002", name="Jam", ingredients=["1 jar jam"], photos=["images/bomb.jpg"]),
    recipe(rid="aaaaaaaa-0000-4000-8000-000000000003", name="Honey", ingredients=["1 spoon honey"], photos=["images/liar.jpg"]),
    recipe(rid="aaaaaaaa-0000-4000-8000-000000000004", name="Salt", ingredients=["1 pinch salt"]),
]
write_zip("recipekeeper-hostile-photos.zip", [
    ("recipes.html", page(*photos)), ("images/good.jpg", GOOD),
    ("images/bomb.jpg", b"\xff\xd8" + b"\x00" * (9 * 1024 * 1024)), ("images/liar.jpg", GOOD + b"liar"),
])
patch_declared_size("recipekeeper-hostile-photos.zip", "images/liar.jpg", 0xFFFFFFF0)
# Nesting 100 000 deep inside a recipe's notes must not overflow a stack, nor lose the recipe.
deep = recipe(
    rid="bbbbbbbb-0000-4000-8000-000000000001", name="Deep", ingredients=["1 egg"], directions=["Boil."], notes=["Fine."],
).replace("<hr/></div>", "<div>" * 100_000 + "<hr/></div>")
write_zip("recipekeeper-deep.zip", [("recipes.html", HEAD.encode() + deep.encode() + TAIL.encode())])
