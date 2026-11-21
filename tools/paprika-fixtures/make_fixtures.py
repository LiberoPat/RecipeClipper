#!/usr/bin/env python3
"""Builds shared/fixtures/paprika/*.paprikarecipes (#382).

A .paprikarecipes file is a zip of `<name>.paprikarecipe` entries, each a gzip-compressed JSON
object (documented by the open-source libpaprika crate: created, difficulty, photo_large, uid,
photo, categories, notes, prep_time, cook_time, source, source_url, photo_data, total_time, name,
ingredients, photos, rating, description, photo_hash, image_url, nutritional_info, directions,
hash, servings). These files are written here, not exported from the app: the recipes are made
up. Run: python3 -I tools/paprika-fixtures/make_fixtures.py <output dir>
"""
import base64
import gzip
import io
import json
import struct
import sys
import zipfile

OUT = sys.argv[1]
DATE = (2024, 1, 1, 0, 0, 0)


def gz(data: bytes) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as f:
        f.write(data)
    return buf.getvalue()


def recipe(**fields) -> bytes:
    base = {
        "uid": "", "name": "", "ingredients": "", "directions": "", "notes": "", "servings": "",
        "prep_time": "", "cook_time": "", "total_time": "", "source": "", "source_url": "",
        "categories": [], "photo_data": None, "image_url": None, "rating": 0, "difficulty": "",
        "nutritional_info": "", "description": "", "on_favorites": 0, "created": "2024-01-01 09:00:00", "hash": "0" * 64,
    }
    base.update(fields)
    return gz(json.dumps(base, ensure_ascii=False).encode("utf-8"))


def write_zip(name, entries):
    with zipfile.ZipFile(f"{OUT}/{name}", "w", zipfile.ZIP_DEFLATED) as z:
        for entry, data in entries:
            info = zipfile.ZipInfo(entry, DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


PHOTO = base64.b64encode(b"\xff\xd8\xff\xe0\x00\x10JFIF-not-a-real-picture").decode()

brownies = recipe(
    uid="7C3B1A52-0F1E-4C8E-9A57-1D2E3F4A5B6C", name="Fudgy Brownies",
    ingredients="For the batter:\n1 cup (225 g) unsalted butter\n1 3/4 cups sugar\n4 large eggs\n3/4 cup cocoa powder\n\nFor the top:\nflaky salt",
    directions="Melt the butter and whisk in the sugar.\n\nBeat in the eggs, then fold in the cocoa.\nBake at 350°F for 30 minutes.",
    notes="Dad's favourite. Use less sugar next time.", servings="12 brownies",
    prep_time="20 mins", cook_time="1 hour 30 minutes", total_time="Overnight", source="Example Kitchen",
    source_url="http://www.Example.com/brownies?utm_source=paprika&id=7#top", categories=["Dessert", "Baking"],
    photo_data=PHOTO, image_url="http://img.example.com/brownies.jpg", rating=5, difficulty="Easy", scale="1x",
    surprise={"nested": [1, 2, 3]},
)
soup = recipe(
    uid="0A9B8C7D-6E5F-4A3B-8C2D-1E0F9A8B7C6D", name="Gran's Lentil Soup",
    ingredients="1 cup red lentils\n1 onion\n4 cups water\nsalt", directions="Simmer everything for 25 minutes.\nBlend.",
    servings="Serves 4", prep_time="PT10M", cook_time="0 mins", categories=["dinner", "Soup"], photo_data=PHOTO,
)
tea = recipe(
    name="Mint Tea", directions="Steep mint leaves in hot water for 5 minutes.", categories=["Drinks", "Baking"], on_favorites=1
)
dupe = recipe(
    uid="11111111-2222-3333-4444-555555555555", name="Brownies again",
    ingredients="1 cup butter", directions="Bake.", source_url="https://www.example.com/brownies?id=7&utm_medium=x",
    categories=["holiday", "BAKING"],
)
no_name = recipe(uid="22222222-0000-0000-0000-000000000000", ingredients="1 egg", directions="Boil it.")
no_content = recipe(uid="33333333-0000-0000-0000-000000000000", name="Just A Title")
junk = [
    ("__MACOSX/._Fudgy Brownies.paprikarecipe", b"\x00\x05\x16\x07 AppleDouble"),
    ("readme.txt", b"hello"),
    ("folder/", b""),
]

write_zip("paprika-valid.paprikarecipes", [
    ("Fudgy Brownies.paprikarecipe", brownies), ("Gran's Lentil Soup.paprikarecipe", soup),
    ("Mint Tea.paprikarecipe", tea), ("Brownies again.paprikarecipe", dupe),
    ("Untitled.paprikarecipe", no_name), ("Just A Title.paprikarecipe", no_content),
] + junk)

# Cut off in the middle: no end-of-central-directory record.
with open(f"{OUT}/paprika-valid.paprikarecipes", "rb") as f:
    data = f.read()
with open(f"{OUT}/paprika-truncated.paprikarecipes", "wb") as f:
    f.write(data[: len(data) * 6 // 10])

# One good recipe among entries that are not gzip, not JSON, or JSON that is not an object.
write_zip("paprika-bad-entries.paprikarecipes", [
    ("Plain JSON.paprikarecipe", json.dumps({"name": "Not gzipped", "ingredients": "1 egg"}).encode()),
    ("Not JSON.paprikarecipe", gz(b"this is not json")),
    ("A list.paprikarecipe", gz(b'[{"name": "In a list", "ingredients": "1 egg"}]')),
    ("Bad UTF-8.paprikarecipe", gz(b'{"name": "\xff\xfe", "ingredients": "1 egg"}')),
    ("Fine.paprikarecipe", tea),
])

# An entry whose zip headers say it is 4 GB when unpacked, beside a good one.
write_zip("paprika-huge-declared.paprikarecipes", [("Liar.paprikarecipe", soup), ("Fine.paprikarecipe", tea)])
with open(f"{OUT}/paprika-huge-declared.paprikarecipes", "rb") as f:
    b = bytearray(f.read())
local = b.find(b"PK\x03\x04")
struct.pack_into("<I", b, local + 22, 0xFFFFFFF0)
central = b.find(b"PK\x01\x02")
struct.pack_into("<I", b, central + 24, 0xFFFFFFF0)
with open(f"{OUT}/paprika-huge-declared.paprikarecipes", "wb") as f:
    f.write(b)

# A small file that unpacks to 40 MB of zeros (over the 32 MiB an entry may hold), beside a good one.
write_zip("paprika-bomb.paprikarecipes", [
    ("Bomb.paprikarecipe", gz(b"\x00" * (40 * 1024 * 1024))), ("Fine.paprikarecipe", tea),
])

# Pictures (#386): a good one, one that isn't an image, one that isn't base64, one over the 8 MiB a picture may be.
b64 = lambda data: base64.b64encode(data).decode()
write_zip("paprika-photos.paprikarecipes", [
    ("Good photo.paprikarecipe", recipe(uid="P1", name="Toast", ingredients="1 slice bread", photo_data=b64(b"\xff\xd8\xff\xe0 a real-enough picture"))),
    ("Not an image.paprikarecipe", recipe(uid="P2", name="Jam", ingredients="1 jar jam", photo_data=b64(b"bad picture, not an image"))),
    ("Not base64.paprikarecipe", recipe(uid="P3", name="Butter", ingredients="1 pat butter", photo_data="!!! this is not base64 !!!")),
    ("Too big.paprikarecipe", recipe(uid="P4", name="Honey", ingredients="1 spoon honey", photo_data=b64(b"\xff\xd8" + b"\x00" * (9 * 1024 * 1024)))),
    ("No photo.paprikarecipe", recipe(uid="P5", name="Salt", ingredients="1 pinch salt")),
])
