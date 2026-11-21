# Site check-up

Twice-monthly scheduled check of how the parsers handle real recipe sites.
Fixes go in `shared/tables/site-rules.json` (with a saved page in
`shared/fixtures/pages` and a test on both platforms); anything that needs
code becomes a `Site: <host> — <what fails>` issue.

## Rotation

Runs cover about 20 sites each, different ones every time. The 2026-11-20 run
was blocked entirely (proxy), so its batch 1 was run on 2026-11-21 with the
sites that were reachable; "not tested" sites go first next run.

Next run, in order: allrecipes, seriouseats, foodnetwork, simplyrecipes,
budgetbytes, thekitchn, halfbakedharvest, sallysbakingaddiction,
smittenkitchen, minimalistbaker, chefkoch.de, lecker.de, kitchenstories,
giallozafferano.it (real recipe URLs; discovery only found roundups),
marmiton.org and cuisineaz.com (real recipe URLs), 750g, recetasgratis,
panelinha, kurashiru, cookpad, rakuten. Then the rest of the schedule list.

## Runs

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
