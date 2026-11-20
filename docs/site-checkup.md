# Site check-up

Twice-monthly scheduled check of how the parsers handle real recipe sites.
Fixes go in `shared/tables/site-rules.json` (with a saved page in
`shared/fixtures/pages` and a test on both platforms); anything that needs
code becomes a `Site: <host> — <what fails>` issue.

## Rotation

Batch 1 (planned for the first run, not yet tested): allrecipes, seriouseats,
foodnetwork, simplyrecipes, budgetbytes, thekitchn, kingarthurbaking,
recipetineats, halfbakedharvest, minimalistbaker, sallysbakingaddiction,
smittenkitchen, tasteofhome, bbc.co.uk/food, jamieoliver, chefkoch.de,
marmiton.org, giallozafferano.it, cookpad.com, tudogostoso.com.br.
Later batches: the rest of the list in the schedule prompt (lecker,
essen-und-trinken, kitchenstories, 750g, cuisineaz, cuisine-actuelle,
recetasgratis, directoalpaladar, cocinafacil, cucchiaio, misya, cookist,
receitasnestle, panelinha, kurashiru, delishkitchen, rakuten).

## Runs

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
