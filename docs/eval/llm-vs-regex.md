# Regex vs on-device LLM vs System One (#105)

Part of #99. Run on 2026-09-25 on the owner's Mac (M1 Pro, 32 GB, macOS 26.6.2) with
`tools/eval/run.sh`. A dev tool only: nothing here ships or changes app behaviour.

## Summary

- **Ingredient amounts: the regex stays.** 98% right with no confident wrong on the corpus, 91%
  on held-out lines, in under a millisecond. A 3B model is about as accurate on held-out lines
  (92%) at ~0.5 s a line, and adds a little only where the regex abstains on purpose. Both fail
  on the same two lines, a real regex bug the evaluation found: "1-1/2 cups" doubled shows
  "2-1 cups" (#125).
- **Pages with no recipe data: the model helps a lot.** From nothing today to 15 of 16 recipes,
  84% of the ingredient lines, no invented line (`PageRecipeCheck` held), but 3 real lines from
  another recipe on the same page slipped in, and one long recipe overflowed 1,024 output tokens.
- **Decisions: act only on aisles.** With `DecisionRule`, the generative model was confidently
  wrong on 4 of 22 count brackets (a wrong figure on screen); JevK5 was no better there. For
  aisles, JevK5 (open weights, 4B) was right on 20 of 20 names the table can't file, at 0.5 s.
  Same-ingredient answers erred only toward "different", which the app never acts on.
- **Chef mode: the check wasn't enough.** Half the steps passed `ShortStepCheck`, but by hand 19
  of those 42 dropped an action or ingredient, or invented one, and no number moved to catch it.
  **#129** made the check keep the words too: on 78 replies read by hand, faithful among shown
  rose from 38% to 76% (unfaithful shown 34 → 4), at the cost of 8 of 21 faithful ones.
- Measured with a 3B stand-in (Apple Intelligence is off on this Mac; Gemini Nano is device
  only), on small samples. The phone models may do better or worse; the harness is ready to
  re-run.

## What ran, and what couldn't

| Arm | Status |
|---|---|
| **Regex / rules as shipped** | Ran. The harness compiles the iOS app's own `Data/Model` sources (pinned to the Kotlin by the differential corpus), so the numbers are the app's. |
| **Apple Foundation Models** | **Not run.** The framework is on this Mac (macOS 26.6), but `SystemLanguageModel.default.availability` is `unavailable(appleIntelligenceNotEnabled)`. Turning Apple Intelligence on is a system setting (and a model download) that is the owner's call, not an agent's. Re-run with it on: see "Re-running". |
| **Gemini Nano (ML Kit GenAI)** | **Device only.** It runs only through AICore on supported Android phones; there is no Mac build. Not faked. |
| **Stand-in generative model** | Ran: **Qwen2.5-3B-Instruct** (Q4_K_M, Ollama 0.34.4, temperature 0), about the size of Apple's ~3B on-device model, with the app's own instructions and JSON schemas (Ollama's `format`, the nearest thing to guided generation). It is a proxy, not the phone model: treat its numbers as "a 3B model with these prompts", not as Apple's or Google's. |
| **Open-weights System One** | Ran: **JevK5-4B v0.3** (Qwen3.5-4B + a distilled LoRA, Apache-2.0, `alibiserikbay/JevK5-GGUF`, Q8_0) through Homebrew's `llama-server`, decisions task only. Installing the `jevk5` Python client was blocked by this session's permissions (external code), so the harness reads the same thing itself: the options as letters, one forward pass, a softmax over the letters' log-probabilities at the card's temperature (1.22). The prompt is ours, not the package's, so this is JevK5's weights with an approximate readout. |
| Paid APIs (Jev cloud) | Not used, by decision (#104). |

## Results

### 1. Ingredient lines: the first amount

Corpus lines (#33 and the rest of the English `Ing` rows; the regex was tuned on these), n = 143:

| Approach | Correct | Confident wrong | Abstained (as written) | Checker rejected | Median latency |
|---|---:|---:|---:|---:|---:|
| Regex (`IngredientScaler`, as shipped) | 140 (98%) | **0** | 3 (2%) | – | <1 ms |
| LLM, number used as returned | 130 (91%) | 1 (1%) | 12 (8%) | – | 457 ms |
| LLM + verbatim-number gate | 129 (90%) | 1 (1%) | 13 (9%) | 1 | 457 ms |
| Regex first, gated LLM where regex abstains | 142 (99%) | **0** | 1 (1%) | – | – |

Held-out lines from the site-check pages (never seen by the regex's authors), n = 64:

| Approach | Correct | Confident wrong | Abstained (as written) | Checker rejected | Median latency |
|---|---:|---:|---:|---:|---:|
| Regex (`IngredientScaler`, as shipped) | 58 (91%) | 2 (3%) | 4 (6%) | – | <1 ms |
| LLM, number used as returned | 59 (92%) | 2 (3%) | 3 (5%) | – | 481 ms |
| LLM + verbatim-number gate | 59 (92%) | 2 (3%) | 3 (5%) | 0 | 481 ms |
| Regex first, gated LLM where regex abstains | 61 (95%) | 2 (3%) | 1 (2%) | – | – |

Names right: regex 123/143 and 54/64, LLM 127/143 and 55/64. LLM unit right: 126/142 and
57/64 (the regex has no separate unit output to score).

Every confident wrong, in full:

- **Regex, a real bug, now #125:** "1-1/2 cups sugar" doubled reads "2-1 cups sugar", and
  "1-3/4 cups all-purpose flour" reads "2-1 1/2 cups …". A hyphenated mixed number (Taste of
  Home's style) is read as a range. The LLM returned "1-1/2" and "1-3/4" correctly as text, but
  the app's parser, which owns the number, reads them the same wrong way, so the gated LLM fails
  on the same two lines.
- **LLM:** "4 ounces ciabatta …, cut into 1-inch cubes (about 3 cups)" read as amount 3.
- What the LLM adds is on the regex's deliberate abstentions: "Two pinches of ground cloves",
  "two 8-ounce packages …", "½ a bunch of fresh thyme (15g)", "3 large apples, … (about 3
  cups)", "1 cup extra-firm tofu* ((8 ounces yields ~1 cup))", "2 tsp sugar ((reduce to 1 tsp if
  using Mirin))". Most of these the regex leaves as written on purpose (a bracket it can't
  classify, a number word), so "correct" there means the model found the right first amount,
  not that scaling the whole line would be safe.
- An earlier prompt without worked examples left the amount empty on about 40% of plain lines
  ("3 eggs", "8 oz cream cheese"); returning `null` was the model's easy way out.

### 2. Pages with no recipe data (#103)

16 pages (15 site-check pages that answered, plus the no-recipe-data fixture):

| Approach | Recipes shown | Name right | Gold ingredient lines found | Ingredient lines shown: right / wrong | Gold steps found | Steps shown: right / wrong | Median latency |
|---|---:|---:|---:|---:|---:|---:|---:|
| Today (parsers only) | 0 | – | 0 / 152 | 0 / 0 | 0 / 86 | 0 / 0 | – |
| Window + LLM + `PageRecipeCheck` | 15 | 12 | 127 / 152 (84%) | 127 / **3** | 56 / 86 (65%) | 84 / 0 | 7.8 s |

- `RecipeTextWindow` found a window on every page, so the model was always asked.
- The checker dropped 6 of 136 ingredient strings and 7 of 91 steps the model returned as not on
  the page. No invented line got through.
- **The 3 wrong lines are all on Delish:** "1 1/2 cups cherry tomatoes, halved", "3 cups baby
  spinach", "1/2 cup heavy cream", from a Tuscan recipe elsewhere on the cookie page, mixed into
  the cookies' ingredients. They are on the page, so `PageRecipeCheck` can't catch them. This is
  the extraction's real risk: never an invented line, but a real line from the wrong recipe.
- Taste of Home's card words its steps differently from its own JSON-LD. The 7 steps the model
  picked there are the card's, so they're counted right after reading them.
- One page (RecipeTin Eats, 20 ingredients and 14 steps) failed: the reply was cut off at
  1,024 output tokens and wasn't valid JSON. That is the limit #103 set on Android, so a long
  recipe may not fit.
- Steps found is the weak number (65%): the model often stops early or merges steps.

### 3. Decisions (#104)

Only the items the app would actually send to the model (`run.sh decisions` also prints the tables
over every labelled item, and the rules' own accuracy: aisles 32 of 52 filed, none wrong). "Rules (today)" abstains on all of them by construction.

| Decision (n asked) | Approach | Correct | Confident wrong | Abstained | Median latency |
|---|---|---:|---:|---:|---:|
| Count brackets (22) | LLM + `DecisionRule` | 14 (64%) | **4 (18%)** | 4 | 867 ms (2 asks) |
| | JevK5, top option | 13 (59%) | 9 (41%) | 0 | 756 ms |
| | JevK5, p ≥ 0.7 | 9 (41%) | 3 (14%) | 10 | |
| | JevK5, p ≥ 0.8 | 4 (18%) | 1 (5%) | 17 | |
| | JevK5, p ≥ 0.9 | 1 (5%) | 0 | 21 | |
| Same ingredient (24) | LLM + `DecisionRule` | 16 (67%) | **3 (12%)** | 5 | 898 ms |
| | JevK5, top option | 21 (88%) | 3 (12%) | 0 | 626 ms |
| | JevK5, p ≥ 0.7 | 10 (42%) | **0** | 14 | |
| | JevK5, p ≥ 0.8 | 4 (17%) | 0 | 20 | |
| Aisle (20) | LLM + `DecisionRule` | 10 (50%) | 0 | 10 | 873 ms |
| | JevK5, top option | 20 (100%) | 0 | 0 | 524 ms |
| | JevK5, p ≥ 0.7 | 18 (90%) | **0** | 2 | |
| | JevK5, p ≥ 0.8 | 15 (75%) | 0 | 5 | |

- **Count brackets, the LLM's four wrongs, all "high" twice:** "6 garlic cloves (30 g)" and "2
  chicken breasts (about 1 lb)" as each (so doubling would show "12 garlic cloves (30 g)"), and
  "2 cenouras grandes (cerca 250 g)" as total and "2 duck breasts (about 350 g)" as each (both
  labelled unsure: no definite answer is safe). Each would put a wrong figure on screen. Two of
  the "correct" lines are the prompt's own examples ("4 apples (about 800 g)", "3 large apples
  …"). JevK5 is barely better than a coin here with our prompt.
- **Same ingredient, the LLM's three wrongs:** "icing sugar" and "confectioners' sugar" against
  "powdered sugar", and "strong white bread flour"/"bread flour", all as *different*: wrong, but
  harmless, since the app only ever acts on "same" (Buy → Have). No pair was wrongly called
  "same" by either model; with the owner's "different" examples in the prompt, both lean to
  "different". JevK5's three top-option wrongs all sat below 0.7.
- **Aisles:** JevK5's top option was right on all 20 names the keyword table puts in Other, and
  51 of all 52 (flour tortillas: baking, at 0.35). The LLM through `DecisionRule` was right on
  10 and abstained on 10, often splitting its two asks ("miso paste": dairy, then spices;
  "gochujang": other, then spices); on names the table already files it said vanilla extract is
  spices and puff pastry bakery, "high" both times.
- Across the two full runs the generative model's decision counts moved by up to 4 items
  (Ollama at temperature 0 is not bit-exact across runs); JevK5's did not move.

### 4. Chef mode (#100)

79 steps (up to 8 per recipe, long enough to send), then `ShortStepCheck`:

| Approach | Short version shown | Rejected: not shorter | Rejected: a new number | Rejected: time/temperature | Mean cut (shown) | Median latency |
|---|---:|---:|---:|---:|---:|---:|
| Today (as written) | 0 | – | – | – | 0% | – |
| LLM + `ShortStepCheck` | 42 (53%) | 18 | 1 | 18 | 38% | 0.6 s |

**Hand review of the 42 shown:** 23 keep everything a cook needs. **19 lose or change
something**, and the check can't see it because no number moved:

- 15 drop an action or an ingredient, or invent one: "Preheat the oven to 375°F. Lightly grease
  … two baking sheets." → "Preheat the oven to 375°F."; a banana-bread step → "Fold in the
  walnuts gently." (the eggs, bananas, oil, buttermilk and mixing gone); "Lay the steak on top …
  and devour." → "Sprinkle with dried herbs before devouring." (invented); "wrap tofu in a towel
  … to press out the liquid" → "Press tofu for an hour." (an invented time, in words, so
  `StepTimers` doesn't see it); "Butter a 6-cup loaf pan or coat it with nonstick spray" →
  "Grease and flour …"; a pancake step's order reversed ("Whisk to a smooth batter, then mix in
  100g plain flour, 2 large eggs …"); Bon Appétit's "preheat to 375°" dropped (a bare degree sign
  isn't a temperature to `TemperatureConverter`, so the check doesn't miss it).
- 4 shift a cue: "until it starts to turn golden" → "until fragrant"; "Alternative: Use 1/3 cup
  …" → "Use 1/3 cup …"; "rest if you have time, or start cooking straight away" → "rest, then
  start cooking".

So about **29% of steps got a short version that is both shorter and faithful**, and 24% of steps
showed one that isn't.

#### Re-run with the word check (#129, 2026-09-26)

A fresh run over the site-check pages (103 steps). Its 78 shorter replies were read by hand into
`tools/eval/gold/chef.jsonl` (21 faithful, 57 not; the recipes' ingredient lines in
`gold/chef-recipes.jsonl`). `run.sh chef --no-llm` replays exactly those replies through the
check; a fresh `run.sh chef` gave the same table.

| Check | Shown | Faithful among shown | Unfaithful shown | Faithful rejected |
|---|---:|---:|---:|---:|
| Before (numbers, times and temperatures) | 55 | 21 (38%) | 34 | 0 of 21 |
| After (#129: the words too) | 17 | 13 (76%) | 4 | 8 of 21 |

- **Still shown, and wrong (4):** a dropped "re-cover" (the step's earlier "cover" is still
  there), "according to the packet instructions" turned into its example's fixed 1 minute, a
  dropped "the tray on the bottom might need a few extra minutes", a dropped "place the other
  cardboard on top".
- **Faithful, but rejected (8):** "if" dropped from an aside (2), a dropped thermometer, bowl or
  "coat", "set oven" for "preheat", an added "placing" or "adhesion". The check has no synonyms,
  on purpose: a doubtful short step shows as written.
- So about 13% of steps now get a short version that is both shorter and faithful (20% before),
  and 4% show one that isn't (33% before).

## Method

**Outcomes.** Every item is scored as one of three things, because the product rule ("never show
a confident wrong number") cares about the difference between them:

- **Correct:** what the app would show is right (for a line with no amount, correctly leaving it
  alone).
- **Confident wrong:** the app would show something wrong: a wrong amount scaled, an amount put on
  a line that has none, a wrong definite decision, a line picked that isn't the recipe's.
- **Abstained:** the app keeps today's behaviour (the line as written, Buy, Other, the step as
  written). Never harmful, only less helpful.

"Checker rejected" counts model answers that a code gate threw away (an abstention it caused).

**1. Ingredient lines** (`gold/ingredients.jsonl`, `gold/ingredients-heldout.jsonl`). The task is
what scaling needs: the line's first amount (a range's lower end), plus its unit and name.

- *Gold:* 143 lines from the differential corpus's English `Ing` rows, which end with the #33 real
  lines, and a **held-out** 64 lines from the site-check pages' JSON-LD that are not in the
  corpus (the regex was never tuned on them). A line went in only when its first amount, unit and
  name can be stated with certainty by reading it; left out: "Juice of 1 lemon", "1-inch piece
  ginger", "11/2 cups", "1,500 g", "250 King Prawns", "Pinch of …" without a number, and anything
  else where a careful cook could read it two ways. Units accept either spelling where both are
  normal ("1 garlic clove": clove or none).
- *Regex:* `IngredientScaler.scale(line, 2)`; unchanged means abstained, otherwise the first
  amount of the doubled line, halved. `IngredientName.of` for the name. The regex has no
  separate unit output (units are consumed inside conversion), so only the LLM's unit is scored.
- *LLM:* one ingredient line in, `{amount, unit, name}` out through a JSON schema, with four
  worked examples in the instructions (without them the 3B model left the amount empty on about
  40% of plain lines). Its amount is then held to #99's rule: the number must appear in the
  line as written, not inside another number, and the app's own `IngredientScaler.parse` must
  read it ("LLM + verbatim-number gate"). "Number used as returned" skips the gate, to show what
  the gate costs.
- *Hybrid:* the regex's answer, and the gated LLM's only where the regex abstains.
- A name is right when it holds the gold name as whole words and adds at most two words.

**2. Pages with no recipe data.** The 22 site-check URLs were fetched once (16 answered; Serious Eats,
Allrecipes, Budget Bytes, Food Network, Simply Recipes and Food & Wine sent 403), plus
`shared/fixtures/pages/blog-no-recipe-data.html`. For each page the gold is the recipe the
parsers find (JSON-LD, else microdata); for the fixture, its card as a reader sees it. The #103
pipeline is then run as if the page had no recipe data: `PageTextReader` (scripts, so the
JSON-LD, left out) → `RecipeTextWindow` at the iOS 26 budget (6,888 characters) → the model with
the iOS extractor's instructions and fields → `PageRecipeCheck` via `PageRecipe.recipe`. A kept
line is right when it matches a gold line after NFKC folding (or one holds the other and the
shorter is at least 60% of the longer: sites add notes in one place and not the other). Today's
behaviour is `NoRecipeFound`: nothing shown, nothing wrong.

**3. Decisions** (`gold/decisions.jsonl`, labelled by hand): 24 count-bracket lines (the corpus's
`Count` rows plus lines in the same shapes; "total|each" where a count of 1 makes both right;
"unsure" where a careful cook couldn't say, so any definite answer is wrong), 32 name pairs
(synonyms across British and American English, and the owner's kind of "different": an extra word
that makes another product), and 52 ingredient names with their aisle. Rules = today: count
brackets stay as written; `IngredientName.matches` for same; the keyword table's aisle
(`Aisles.ofName`), Other being an abstention. The generative model goes through the app's own
`DecisionPrompts` and `DecisionRule` (asked twice, options reversed the second time, both must
agree and say "high"), with the answer constrained to the listed options. JevK5 answers from its
option probabilities, acting only above a threshold (0.8, 0.9, 0.95 shown). Each table also says
how many items the app would actually send to the model (`needsCountDecision`; a pair that
doesn't already match and is `DecisionCandidates.close`; a name the table puts in Other).

**4. Chef mode.** Up to 8 steps per gold recipe (English, at least `ShortStepCheck.worthShortening`),
shortened with the iOS `StepShortener` instructions, then `ShortStepCheck.accept`. Accepted
pairs were read by hand for meaning, which the check can't judge. For #129 every shorter reply
was read by hand, whether the check passed it or not: faithful means a cook reading only the
short step does the same thing (every action, ingredient, piece of equipment and cue kept,
nothing added). The check gets the recipe's ingredient lines, as the app's repositories pass
them.

## Caveats

- **The Mac model is not the phone model.** Qwen2.5-3B stands in for Apple's ~3B on-device
  model and Gemini Nano; they are different models with different training, and Apple's guided
  generation constrains output more tightly than a JSON schema through Ollama. Every LLM number
  here is "a 3B model with the app's prompts". Re-run with Apple Intelligence on, and on an
  Android phone, before shipping any flag.
- **Small samples.** 143 + 64 lines, 16 pages, 22/24/20 decisions asked, 79 steps. One item is
  1.5–5 points; differences under ~10 points between arms are noise.
- **Not deterministic.** Ollama at temperature 0 gave different answers on a few items between
  runs (up to 4 decisions, a handful of lines); the tables are from one run each.
- **The corpus favours the regex:** its lines are the ones the regex was fixed against (#33).
  The held-out set is the fairer comparison, and still small.
- **Gold by one reader.** Lines, decisions and step reviews were labelled by hand by the agent
  that ran the harness, only where the answer was clear (the exclusions are listed in Method);
  a second reader would tighten it. The Chef mode review is a judgement call per step.
- **Prompt effort.** The ingredient prompt got one round of worked examples; the others are the
  app's own prompts, unchanged. Better prompts could move the LLM numbers.
- **JevK5 through our readout,** not its package (blocked here), and at our prompt; its
  probabilities are sharper or flatter with its own template. Its 0.7 threshold was read off
  this data, so it is optimistic.
- **Pages:** the model saw page text with the JSON-LD removed, not pages that truly have none
  (only the fixture is one). Real no-data pages may be messier. Latency is on an M1 Pro GPU; a
  phone will be slower.

## Recommendation

**Where the regex stays: ingredient amounts, scaling and conversion.** It is as accurate as the
model on unseen lines, hundreds of times faster, needs no model, and its failures are fixable
bugs rather than drift (fix #125). Don't add an LLM amount parser. If anything, a model could
later be asked only about the lines the regex leaves as written, and only through the same
verbatim-number gate; that adds 1–4 points and isn't worth a feature yet.

**Where the LLM helps: page extraction (#103).** Keep it, behind its flag, and consider two
changes before turning it on:

1. Raise Android's 1,024 output tokens (a 20-ingredient recipe didn't fit), or fail such a page
   quietly as today (it does).
2. Guard against a real line from the wrong recipe: for example, keep only the ingredient lines
   that sit in one run under the chosen ingredients heading in the window, or drop picked lines
   far from the rest. The check can't catch these today because they are on the page.

**Decisions (#104):**

- **Aisles:** a clear win at no risk (a wrong aisle is a nuisance, not a wrong number). JevK5
  at p ≥ 0.7 filed 18 of 20 names the table can't, with no wrong answer, and the generative
  model with `DecisionRule` filed 10. Worth evaluating a JevK5-class classifier on the device if
  one ever fits, and meanwhile keeping the generative path behind the flag.
- **Count brackets: don't act on them yet.** Both models were confidently wrong on about 1 in 5
  (18% with `DecisionRule`), and each wrong answer is a wrong figure on screen, exactly what the
  product rule forbids. Keep these lines as written until a phone model does much better on this
  set, or require a third, differently worded ask to agree.
- **Same ingredient:** safe as built (only "same" acts, and neither model said "same" wrongly),
  but it rarely helps: most synonyms that matter (courgette/zucchini, cilantro/coriander) aren't
  "close", so the app never asks. A small synonym table would do more than the model here.

**Chef mode (#100): keep it off until the check covers dropped content.** `ShortStepCheck` stops
wrong numbers, but by hand 19 of the 42 short steps it let through lost or invented something.
A cheap extra gate: every ingredient name (from the recipe's own lines) and every cooking verb
of the step must still be in the short version; that would have caught about half of these.
Then re-measure with Apple's and Google's models, which are tuned for exactly this rewrite.
**Done in #129** (the re-run above): the gate now also keeps ingredients, actions, equipment,
qualifiers and time words and allows no new word; Chef mode stays off until a phone model is
measured with it.

**Nothing in the app changes in this PR.** The follow-ups are #125 (the regex bug), and the
suggestions above, each the owner's call.

## Re-running

```
brew install ollama llama.cpp
ollama serve &            # then: ollama pull qwen2.5:3b
llama-server --hf-repo alibiserikbay/JevK5-GGUF --hf-file jevk5-4b-v0.3-Q8_0.gguf -c 8192 -ngl 99 --port 8090 &
tools/eval/fetch-pages.sh # the site-check pages, cached in tools/eval/.cache (never committed)
tools/eval/run.sh         # or: run.sh ingredients decisions, or --no-llm for the rules alone
```

Results land in `tools/eval/results/` (a Markdown summary and one `.tsv` per task with every
item's output). `EVAL_MODEL=<ollama tag>` swaps the generative model. An Apple Foundation Models
arm would be a small addition to `Llm.swift` (`LanguageModelSession`, `@Generable`) once Apple
Intelligence is on.
