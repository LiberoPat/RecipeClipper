# Site check-up

Twice-monthly scheduled check of how the parsers handle real recipe sites.
Fixes go in `shared/tables/site-rules.json` (with a saved page in
`shared/fixtures/pages` and a test on both platforms); anything that needs
code becomes a `Site: <host> — <what fails>` issue.

## Rotation

Runs cover about 20 sites each, different ones every time. The 2026-11-20 run
was blocked entirely (proxy), so its batch 1 was run on 2026-11-21 with the
sites that were reachable; "not tested" sites go first next run.

Next run, in order: cookpad, then the rest of the schedule list. The 2026-11-21
recheck from a home connection covered the 21 sites the cloud run missed; the 8 that
block a plain fetch even there (allrecipes, simplyrecipes, seriouseats, chefkoch.de,
budgetbytes, foodnetwork, halfbakedharvest, sallysbakingaddiction) can only be checked
through the app's rendered fetch.

## Runs

### 2026-11-21 recheck from a home connection

The 21 sites the cloud run couldn't test, rechecked from the owner's Mac on a home
connection. Method: real recipe URLs from each site's home or category page, one plain GET
each (curl, a mobile Safari user-agent, `-L`, robots.txt respected, a second or two
apart), run through `BlogPageParser.parse` (JVM) and compared with the page. "Blocked" is
blocked to a plain fetch only: the app then tries its rendered fetch (and Cloudflare's
check, #220), so it is not a failure in the app.

| Site | Result |
|---|---|
| allrecipes, simplyrecipes, seriouseats | blocked (403, Dotdash Meredith's script page; home and a recipe URL) |
| chefkoch.de | blocked (403, empty body) |
| budgetbytes, halfbakedharvest, sallysbakingaddiction | blocked (403, Cloudflare "Just a moment") |
| foodnetwork | blocked (403, Akamai "Access Denied") |
| thekitchn (3) | pass; its "Recipe Notes" HowToTips become the last step, as on King Arthur (#432, commented) |
| smittenkitchen (4) | issue #437: Jetpack microdata loses the group headings; a 2015 post has no markup at all (NoRecipeFound, so the on-device pick or Clip it yourself, as designed) |
| marmiton.org (3) | pass |
| cuisineaz.com (3) | pass, but "Pour la marinade" is lost: issue #439 |
| giallozafferano.it (3) | issue #438: photo numbers in every step (like #433); group headings lost: #439 |
| lecker.de (3) | pass |
| kitchenstories.com (3) | pass |
| minimalistbaker (3) | pass (group headings kept) |
| recetasgratis.net (now recetas.elperiodico.com) (7) | fixed (rule): the video pointer and call-for-comments endings; one ending stays: issue #440 |
| panelinha.com.br (3) | issue #436: no steps on any page (HowToStep text inside a HowToDirection); its groups are lost too |
| kurashiru.com (3) | pass (ja), but its groups (鶏団子, つゆ) are lost: #439 |
| recipe.rakuten.co.jp (3) | pass (ja; the ★ marks are the site's own) |
| 750g.com (3) | fixed (rule): the brookie's Brownie and Cookie groups get their headings |

Languages were detected right on every page (fr, it, de, es, pt-br, ja; en/en-us).
Rules added (`site-rules.json` version 2): `750g.com` cards; `recetas.elperiodico.com` and
`recetasgratis.net` stepNoise ("Si quieres ver la preparación con más detalle", "Si te ha
gustado esta receta, tienes algún comentario", "Cuéntanos en los comentarios"). Rules go by
the host of the link the cook shared, not the one it redirects to, so the moved site is
listed under both. Fixtures: `site-750g-brookie`, `site-recetasgratis-*`; both pages are in
`site-check-urls.txt`.

### 2026-11-21

Method: plain GET of recipe URLs found from each site's listing page, run
through `BlogPageParser.parse` (JVM) and compared with the JSON-LD.

| Site | Result |
|---|---|
| bbc.co.uk/food (3) | pass |
| recipetineats.com (3) | pass (group headings kept) |
| jamieoliver.com (3) | pass |
| tasteofhome.com (3) | pass; "Editor's Tip:" sits inside steps, which is the site's own method text, so no rule |
| delish.com (3) | pass; the "Make Ahead" note is its own last step |
| essen-und-trinken.de (3) | pass |
| cucchiaio.it, misya.info (3 each) | pass |
| tudogostoso.com.br (3) | pass |
| delishkitchen.tv (3) | pass |
| directoalpaladar.com (3) | pass (the method is one step, as the site gives it) |
| kingarthurbaking.com (3) | issue #432: HowToTip entries become steps |
| cookist.it (3) | issue #433: photo markers "(1)" left in steps |
| marmiton.org, cuisineaz.com | pages found were roundups, not recipes; retest |
| allrecipes, simplyrecipes, seriouseats | blocked (HTTP 402) |
| chefkoch.de, budgetbytes, foodnetwork, halfbakedharvest, sallysbakingaddiction, thekitchn | blocked (403) |
| smittenkitchen | blocked (429) |
| giallozafferano, lecker, kitchenstories, minimalistbaker, recetasgratis, panelinha, kurashiru, rakuten, 750g | no recipe page found from the listing; retest |

Video links: the repo has no YouTube, TikTok or Instagram handling, so
nothing to check or break.
Rules added: none (`site-rules.json` unchanged).

### 2026-11-20 — nothing could be tested

Every outbound request to a recipe site was refused by the session's egress
proxy (HTTP 403 on CONNECT; allrecipes, chefkoch, bbc.co.uk, seriouseats,
marmiton, giallozafferano, cookpad, budgetbytes, youtube.com, reddit.com
all failed). Fix: allow those hosts in the cloud environment's network
settings, or run the check-up in an environment with unrestricted network.

No open issue is a site report (#321 and #314 are feature requests).

| Site | Result |
|---|---|
| All 20 in batch 1 | blocked (proxy) |

Video links (YouTube / TikTok / Instagram): not checked, same reason.
Rules added: none.
