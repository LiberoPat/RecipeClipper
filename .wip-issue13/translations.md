# Translations

The UI is in English, Spanish (`es`), French (`fr`), German (`de`), Italian (`it`) and
Brazilian Portuguese (`pt-BR`) (#13). **The five translations are drafts: a native speaker
should review each one before release,** starting with the terms listed below.

## Where the words live

- **Android:** `app/src/main/res/values/strings.xml` (English) and `values-es`, `values-fr`,
  `values-de`, `values-it`, `values-pt-rBR`. Lint's `MissingTranslation` fails the build
  if a string is missing from any of them. Strings that aren't words (`app_name`,
  `home_title`, `cook_ingredients_count`, `timer_running`) are `translatable="false"`.
- **iOS:** `ios/RecipeClipper/Resources/Localizable.xcstrings`, a String Catalog.
  `Strings.swift` is a set of thin `String(localized:)` accessors, so call sites don't
  change. The catalog keys are the Android resource names, plus the specifiers Swift
  interpolation adds (`servings %lld`, `delete_recipe_title %@`), so one name finds a
  string on both platforms. Every entry is `extractionState: manual`: Xcode doesn't
  manage the keys. `LocalizationTests` fails if a language is missing a key or a plural.
- **The share text** (`RecipeShareText`) takes its labels ("Serves", "INGREDIENTS",
  "Prep"…) from the UI layer, which reads them from the resources above, so a recipe
  shared from a German phone reads "Portionen: 4 … ZUTATEN". The recipe's own text is
  never translated.
- **The site check's report** (`SiteReport`) stays in English: it's a GitHub issue
  body, not UI.

Adding a string: add the English, then all five translations, on both platforms. Mark
the new term in the list below if you weren't sure of it.

## Plurals

Counts go through the platform's plural rules (Android `<plurals>`, catalog plural
variations), never `n == 1`. French treats 0 as singular ("0 recette"), which the rules
handle. Spanish, French, Italian and Portuguese also have a `many` form for round
millions ("1 000 000 de recettes"); it's filled in so lint's `MissingQuantity` is quiet.
The Lists screen still says "Empty" rather than a zero count, in every language.

## Choices made

- **Register:** informal where the language's apps usually are. Spanish *tú*, German *du*,
  Italian *tu*, Portuguese *você*; French uses *vous*, as French apps do.
- **Settings is named as each platform names it:** French "Paramètres" on Android,
  "Réglages" on iOS; Portuguese "Configurações" on Android, "Ajustes" on iOS.
- **Quotation marks follow the language:** «…» (es, it), « … » with non-breaking spaces
  (fr), „…“ (de), “…” (pt-BR).
- **Dates:** Android's `time_date_format` is a pattern and is translated per language
  (`d. MMM` in German, `d 'de' MMM` in Portuguese). iOS uses a template that
  `DateFormatter` reorders itself, so it isn't in the catalog.
- **Unit option names** match the metric vocabulary of each language (Gramos, Grammes,
  Gramm, Grammi, Gramas), and "As written" is the same phrase for units and oven
  temperature ("Wie im Rezept", "Comme dans la recette"…).

## Terms to check

Cooking vocabulary first; these are the ones the draft was least sure of.

| Term | es | fr | de | it | pt-BR |
|---|---|---|---|---|---|
| Serves (stepper label) | Raciones (LatAm: Porciones?) | Portions | Portionen | Porzioni | Porções |
| Makes (yield of things) | Rinde | Quantité | Ergibt | Resa | Rende |
| Prep (time) | Preparación | Préparation | Vorbereitung | Preparazione | Preparo |
| Cook (time) | Cocción | Cuisson | Garzeit | Cottura | Cozimento |
| Instructions (heading) | Elaboración (LatAm: Preparación?) | Préparation (same word as Prep, as on Marmiton) | Zubereitung | Procedimento | Modo de preparo |
| Step | Paso | Étape | Schritt | Passaggio | Passo |
| Start cooking | Empezar a cocinar | Commencer à cuisiner | Jetzt kochen | Inizia a cucinare | Começar a cozinhar |
| Continue cooking | Seguir cocinando | Reprendre la recette | Weiterkochen | Continua a cucinare | Continuar cozinhando |
| Done — next step | Hecho — siguiente paso | Terminé — étape suivante | Fertig – nächster Schritt | Fatto — passaggio successivo | Feito — próximo passo |
| Start 10 min timer | Temporizador de 10 min | Minuteur de 10 min | Timer für 10 min starten | Avvia timer di 10 min | Timer de 10 min |
| Reset (timer) | Reiniciar | Réinitialiser | Zurücksetzen | Azzera | Zerar |
| Time's up | Se acabó el tiempo | Temps écoulé | Zeit ist um | Tempo scaduto | O tempo acabou |
| Dark while cooking | Oscuro al cocinar | Sombre en cuisine | Dunkel beim Kochen | Scuro in cucina | Escuro ao cozinhar |
| Ounces (unit option) | Onzas | Onces | Unzen | Once | Onças (rare in Brazil) |
| Also convert liquids / pourables | Convertir también los líquidos | Convertir aussi les liquides | Auch Flüssigkeiten umrechnen | Converti anche i liquidi | Converter também os líquidos |

Also worth a look: "Undo" and "Cancel" are the same word in French (Annuler) and Italian
(Annulla), as the platforms themselves have it; "Delete" is *Excluir* in Portuguese
(the platform word) rather than *Apagar*.

## Not translated yet

- **The seeded list names** (Favorites, Lunch, Dinner, Desserts, Breakfast, Snacks) are
  rows in the database, written in English when it's created, and a user can rename them.
  Showing them translated needs a rule for "not renamed yet"; it's left for a follow-up.
- **Times from the recipe** ("1h 30m") are built by the parser with English unit letters,
  and the recipe text itself is shown as the site wrote it. Reading recipes written in
  other languages is #12 and #14–#16.
