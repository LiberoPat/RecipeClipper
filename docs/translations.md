# Translations

The UI is in English, Spanish (`es`), French (`fr`), German (`de`), Italian (`it`) and
Brazilian Portuguese (`pt-BR`) (#13). **The five translations are drafts: a native speaker
should review each one before release,** starting with the terms listed below.

## Review, 2026-10-22

Claude reviewed all five languages, as a careful translator would, not as a native speaker:
every UI string on both platforms, the tour's sample recipe and the terms table. The first
pass found 296 things: its 37 errors and 176 improvements it was sure of are applied, on
Android and iOS alike. A second, closer pass decided the 83 it wasn't sure of: 76 are
applied, with the other strings a changed term touches, and the 7 it kept or doubted are
under "Proposals for a native speaker" below, with the questions still open. A last check
of the applied changes adjusted three tooltips and left one more proposal there. None of this
replaces a native speaker's review.

## Review, 2026-11-18

Claude reviewed the strings the Recipes library work (#345: PRs #346 and #347) added or
reworded, in the same way: the "no history" wording, the Recipes filter, the views strip
(Not in a list, Cooked, Typed by you) with its notes and empty states, "See all", the
clean-up note and the free-tier and delete texts that now say what the library keeps. Each of
the 23 keys was checked against its English, the terms table and the vocabulary the rest of
that language's UI already uses, on both platforms; Android and iOS say the same thing. No
translation still says "history". Placeholders and plural forms were already intact. Twenty-one
strings per platform were changed, identically on both:

- **Italian said "preparata" where the app says "cucinata".** The strip label, its note and the
  empty state told the cook to "Segna come preparata", but the menu item is "Segna come
  cucinata" (and the sort is "Cucinate di recente"). Now "Cucinate" throughout.
- **"Saved" means "in a list" in this app** (*Guardada*, *Enregistrée*, *Gespeichert*,
  *Salvata*, *Salva*), so the "Not in a list" note can't say a recipe was "saved" without a list.
  It now says opened, or clipped by hand, using the words of "Clip it yourself" (*Recortar a
  mano*, *Découper à la main*, *Selbst ausschneiden*, *Ritaglia a mano*, *Recortar à mão*).
- **German:** "Mit + tippst oder scannst du ein Rezept" was missing its separable prefix, now
  "Mit + tippst du ein Rezept ein oder scannst eines". The cooked note's "ein Foto behalten"
  is now "ein Foto hinzugefügt", with both clauses finished. "Darüber werden … entfernt" is
  "Sind es mehr, werden … entfernt". "Bleiben in deinen Rezepten" repeated itself and now names
  the tab, „Rezepte“, in `delete_list_body` and `update_from_source_clip_body`.
- **French:** "Saisies par vous" (stiff) is "Saisies à la main", which also covers scanned and
  pasted recipes as its note says. "Numériser" is "scanner" in the typed note and empty state,
  as in "Scanner une recette".
- **Spanish, Italian, Portuguese:** "Guarda una en una lista" (and "Salvane una in una
  lista", "Salve uma em uma lista") named the recipe, now "una receta", "una ricetta", "uma
  receita"; Spanish "Pasado ese número" is "Pasado ese límite" and Italian "Oltre, le prime a
  essere eliminate" is "Oltre questo limite, vengono eliminate per prime".

The rest was kept: the filter and strip labels, the free-tier texts (the free app keeps N
recipes), the "wasn't added" plurals (every form, the `many` ones included) and the delete
confirmations are right as they were. The doubtful ones are under "Proposals for a native
speaker" below.

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
  never translated. **The same labels live once more, per language, in
  `shared/tables/<lang>/splitter.json` under `shareText`** (#334), so a recipe shared from
  one phone is read back on another whatever its UI language: change a share label and
  change the table with it (`ShareLabelsTableTest` on Android and `ShareLabelsTableTests` on
  iOS fail until both agree). `{n}` stands for the number.
- **The site report** (`SiteReportLink`) stays in English: it's a GitHub issue body the
  maintainer reads, not UI. Its button, "Report this site", is translated.
- **The tour's sample recipe** (#151) is content in each UI language, once, in
  `shared/sample/recipe.json`: written as a cook in that language would (metric, local
  ingredient names), not word for word from the English. A draft like the rest.
- **The tooltips** (#190, `tooltip_*`) name the app's buttons and menu items, so they must follow
  any change to those labels ("As written", "Metric", "Ounces", "Done — next step", "Add to plan", "Send as file", "Mark as
  cooked", "What I need", "Send list", "Paste a list"). The recipe menu's has two versions, with
  and without the meal plan's items.

Adding a string: add the English, then all five translations, on both platforms. Mark
the new term in the list below if you weren't sure of it.

## Plurals

Counts go through the platform's plural rules (Android `<plurals>`, catalog plural
variations), never `n == 1`. French treats 0 as singular ("0 recette"), which the rules
handle. Spanish, French, Italian and Portuguese also have a `many` form for round
millions ("1 000 000 de recettes"); it's filled in so lint's `MissingQuantity` is quiet.
A message with two numbers ("3 recipes weren't added: the free app keeps 20") is one plural
on both platforms, chosen by its count, the first number; `LocalizationTests` checks it.
The Lists screen still says "Empty" rather than a zero count, in every language.

## Choices made

- **Register:** informal where the language's apps usually are. Spanish *tú*, German *du*,
  Italian *tu*, Portuguese *você*; French uses *vous*, as French apps do.
- **Settings is named as each platform names it:** French "Paramètres" on Android,
  "Réglages" on iOS; Portuguese "Configurações" on Android, "Ajustes" on iOS.
- **Quotation marks follow the language:** «…» (es, it), « … » with non-breaking spaces
  (fr), „…“ (de), “…” (pt-BR).
- **No "history" (#345):** the Recipes library is "your recipes" / "Recipes" (*tus recetas*,
  *vos recettes*, *deinen Rezepten*, *tue ricette*, *suas receitas*), never the old *historial*,
  *historique*, *Verlauf*, *cronologia* or *histórico*. The filter under search reads *Todas /
  En una lista / En ninguna lista*, *Toutes / Dans une liste / Dans aucune liste*, *Alle / In
  einer Liste / In keiner Liste*, *Tutte / In una lista / In nessuna lista*, *Todas / Em uma lista
  / Em nenhuma lista*: drafts for a native speaker, like the rest.
- **Dates:** Android's `time_date_format` is a pattern and is translated per language
  (`d. MMM` in German, `d 'de' MMM` in Portuguese). iOS uses a template that
  `DateFormatter` reorders itself, so it isn't in the catalog.
- **Unit option names** match the metric vocabulary of each language (Métrico, Métrique,
  Metrisch, Metrico), and "As written" is the same phrase for units and oven
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
| Done — next step | Hecho — siguiente paso | Terminé — étape suivante | Fertig – nächster Schritt | Fatto — passaggio successivo | Pronto — próximo passo |
| Start 10 min timer | Temporizador de 10 min | Minuteur de 10 min | Timer für 10 min starten | Avvia timer da 10 min | Timer de 10 min |
| Reset (timer) | Reiniciar | Réinitialiser | Zurücksetzen | Azzera | Zerar |
| Time's up | Se acabó el tiempo | Temps écoulé | Zeit ist um | Tempo scaduto | O tempo acabou |
| Dark while cooking | Modo oscuro al cocinar | Mode cuisine sombre | Dunkel beim Kochen | Scuro in cucina | Escuro ao cozinhar |
| Cook mode (the tooltips) | Modo cocina | Mode cuisine | Kochmodus | Modalità cucina | Modo cozinha |
| Chef mode (#100) | Modo chef | Mode chef | Profimodus | Modalità chef | Modo chef |
| Ounces (unit option) | Onzas | Onces | Unzen | Once | Onças (rare in Brazil) |
| Also convert liquids / pourables | Convertir también los líquidos | Convertir aussi les liquides | Auch Flüssigkeiten umrechnen | Converti anche i liquidi | Converter também os líquidos |
| Add to groceries (#50) | Añadir a la compra | Ajouter aux courses | Zum Einkauf hinzufügen | Aggiungi alla spesa | Adicionar às compras |
| Aisle (#50) | Pasillo | Rayon | Abteilung | Reparto | Seção |
| Fruit & vegetables (aisle) | Frutas y verduras | Fruits et légumes | Obst & Gemüse | Frutta e verdura | Hortifrúti |
| Cans & jars (aisle) | Conservas | Conserves | Konserven | Conserve | Enlatados e conservas |
| Pantry (#51) | Despensa | Garde-manger | Vorrat | Dispensa | Despensa |
| Always have (a staple, #51) | Siempre en casa | Toujours en réserve | Immer im Haus | Sempre in casa | Sempre tenho |
| Use by (#51) | Consumir antes del | À consommer jusqu'au | Verbrauchen bis | Da consumare entro il | Vence em |
| What I need (#51) | Lo que necesito | Ce qu'il me faut | Was ich brauche | Cosa mi serve | O que preciso |
| Done shopping (#146) | Compra terminada | Courses terminées | Einkauf erledigt | Spesa fatta | Compras feitas |
| Put away and clear (#146) | Guardar y quitar de la lista | Ranger et retirer | Einräumen und entfernen | Metti via e togli dalla lista | Guardar e remover |
| On your grocery list (a pantry row's basket tag, spoken; it shows the Groceries tab's label, #146) | En tu lista de la compra | Sur votre liste de courses | Auf deiner Einkaufsliste | Nella tua lista della spesa | Na sua lista de compras |
| Remove from grocery list (the tag's action, spoken) | Quitar de la lista de la compra | Retirer de la liste de courses | Von der Einkaufsliste entfernen | Rimuovi dalla lista della spesa | Remover da lista de compras |
| The basket tag (the Pantry tooltip, #194) | la etiqueta de la cesta | l'étiquette panier | die Korb-Markierung | l'etichetta col cestino | a etiqueta da cesta |
| Clear run-out items / Clear 3 run-out items? (Pantry menu, #194) | Quitar los artículos agotados / ¿Quitar 3 artículos agotados? | Retirer les articles épuisés / Retirer 3 articles épuisés ? | Aufgebrauchte Artikel entfernen / 3 aufgebrauchte Artikel entfernen? | Rimuovi gli articoli finiti / Rimuovere 3 articoli finiti? | Remover itens que acabaram / Remover 3 itens que acabaram? |
| Removed from groceries / Removed 2 items from groceries (the basket tag, tapped) | Quitado de la lista de la compra / 2 artículos quitados… | Retiré de la liste de courses / 2 articles retirés… | Von der Einkaufsliste entfernt / 2 Artikel von der Einkaufsliste entfernt | Rimosso dalla lista della spesa / 2 articoli rimossi… | Removido da lista de compras / 2 itens removidos… |
| Update the pantry (after cooking, #147) | Actualizar la despensa | Mettre à jour le garde-manger | Vorrat aktualisieren | Aggiorna la dispensa | Atualizar a despensa |
| Keep / Running low / Out (#147) | Dejar igual / Queda poco / Agotado | Inchangé / Presque fini / Épuisé | Unverändert / Wird knapp / Aufgebraucht | Invariato / Sta finendo / Finito | Manter / Acabando / Acabou |
| “In your pantry” means you have some, not enough (#51) | «En tu despensa» significa que tienes algo… | « Dans votre garde-manger » signifie que vous en avez… | „In deinem Vorrat“ heißt, dass du etwas davon hast… | «Nella tua dispensa» vuol dire che ne hai… | “Na sua despensa” quer dizer que você tem um pouco… |
| “e.g. 3 onions” (the Pantry field's example, #302: an amount the language's own tables split) | p. ej., 3 cebollas | p. ex. 3 oignons | z. B. 3 Zwiebeln | es. 3 cipolle | ex.: 3 cebolas |
| You have 3 lb · this week's recipes use 4 lb (What I need, #302; never a comparison) | Tienes 3 lb · las recetas de esta semana usan 4 lb | Vous avez 3 lb · les recettes de cette semaine en utilisent 4 lb | Du hast 3 lb · die Rezepte dieser Woche verwenden 4 lb | Hai 3 lb · le ricette di questa settimana ne usano 4 lb | Você tem 3 lb · as receitas desta semana usam 4 lb |
| Google's on-device AI (Chef mode on an unsupported phone, #144) | la IA de Google en el dispositivo | l'IA embarquée de Google | Googles On-Device-KI | l'IA di Google sul dispositivo | a IA do Google no dispositivo |
| Your cooks (heading, #116) | Lo que has cocinado | Vos réalisations | Selbst gekocht | I tuoi piatti | O que você fez |
| I made this (#116) | Lo he hecho | Je l'ai faite | Hab ich gekocht | L'ho preparata | Eu fiz |
| Mark as cooked / Cooked (no photo, #173) | Marcar como cocinada / Cocinada | Marquer comme cuisinée / Cuisinée | Als gekocht markieren / Gekocht | Segna come cucinata / Cucinata | Marcar como feita / Feita |
| Read the photo / Check the recipe (#198) | Leer la foto / Revisa la receta | Lire la photo / Vérifier la recette | Foto lesen / Rezept prüfen | Leggi la foto / Controlla la ricetta | Ler a foto / Confira a receita |
| Scan a recipe / Page 1 of 2 (#226) | Escanear una receta / Página 1 de 2 | Scanner une recette / Page 1 sur 2 | Rezept scannen / Seite 1 von 2 | Scansiona una ricetta / Pagina 1 di 2 | Escanear uma receita / Página 1 de 2 |
| Check these lines: the photo was hard to read there (#198) | Revisa estas líneas: ahí la foto se leía mal | Vérifiez ces lignes : la photo y était difficile à lire | Prüfe diese Zeilen: Dort war das Foto schwer zu lesen | Controlla queste righe: in quei punti la foto era poco leggibile | Confira estas linhas: ali a foto estava difícil de ler |
| In this step / Later (cook mode's ingredients bar, #286) | En este paso / Más adelante | Pour cette étape / Pour la suite | In diesem Schritt / Später | In questo passaggio / Più avanti | Neste passo / Depois |
| In a list / Not in a list / Cooked / Typed by you (the Recipes filter and views strip, #345) | En una lista / En ninguna lista / Cocinadas / Escritas por ti | Dans une liste / Dans aucune liste / Cuisinées / Saisies à la main | In einer Liste / In keiner Liste / Gekocht / Selbst eingegeben | In una lista / In nessuna lista / Cucinate / Scritte da te | Em uma lista / Em nenhuma lista / Feitas / Digitadas por você |

Also worth a look: "Undo" and "Cancel" are the same word in French (Annuler) and Italian
(Annulla), as the platforms themselves have it; "Delete" is *Excluir* in Portuguese
(the platform word) rather than *Apagar*.

## Proposals for a native speaker (2026-10-22 review)

What the review kept or wasn't sure enough to change. Each is a matter of taste or needs a
native ear; the reason is the later passes'.

**Spanish**

| Key | Current | Proposed | Reason |
|---|---|---|---|
| `tab_groceries` | Compras | Compra | Spain says "la compra", as the app does elsewhere ("lista de la compra"), but a bare "Compra" on a tab can read as "buy!" or one purchase. Kept. |
| `aisle_spices` | Especias | Hierbas y especias | Shops in Spain label that aisle "Especias", dried herbs included. Kept. |

**Italian**

| Key | Current | Proposed | Reason |
|---|---|---|---|
| `time_just_now` | Proprio ora | Adesso | Both are used for timestamps in Italian apps. Kept. |
| `pantry_always_have_detail` | I prodotti di base non finiscono mai in «Da comprare». | I prodotti di base non compaiono mai in «Da comprare». | In the Pantry, where "Finito" means run out, "non finiscono mai" first reads as "never run out"; "compaiono" is unambiguous. A later check wasn't sure. |
| `action_add_to_plan`, `action_remove_from_plan`, `snackbar_removed_from_plan` | Aggiungi al piano / Rimuovi dal piano | Aggiungi al programma / Rimuovi dal programma | "piano" alone is ambiguous, but beside "Aggiungi alla spesa" it's understood, and "programma" is no less bare. Kept; `delete_menu_body`'s "piano dei pasti" can stay either way. |

**Portuguese**

| Key | Current | Proposed | Reason |
|---|---|---|---|
| `home_type_hint` | Digite você mesmo | Escreva do zero | The generic masculine is normal ("Faça você mesmo"), and "do zero" means "from scratch". Kept. |
| `aisle_baking` | Confeitaria | Farinhas e confeitaria | The aisle holds flour, sugar and yeast first; "Confeitaria" is a real supermarket category, the longer label isn't one shops use. Kept. |
| `action_choose_photos` (iOS) | Escolher da galeria | Escolher da Fototeca | iOS in Portuguese calls the photo library "Fototeca" ("Galeria" is Android's word), but check the name on a pt-BR iPhone's picker first. |

**Library views and notes (2026-11-18 review)**

| Key | Current | Proposed | Reason |
|---|---|---|---|
| `recipes_view_not_in_list_note` (all five) | opened or "clipped by hand" with the "Clip it yourself" words | only "opened" | The English says "clipped or opened". A recipe from a shared link is opened, the hand-clipped one has its own name; whether "recortaste a mano", "découpées à la main", "selbst ausgeschnitten", "ritagliato a mano", "recortou à mão" reads naturally here, or just "opened" is plainer, needs a native ear. |
| `recipes_view_typed` (fr) | Saisies à la main | Écrites par vous / Saisies par vous | "À la main" suits the note (typed, scanned or pasted), but is "main" read as handwriting? |
| `recipes_view_typed` (de) | Selbst eingegeben | Selbst eingetippt | The action and note say "eintippen"; "eingegeben" also covers scanned and pasted. Kept. |
| `recipes_view_cooked` (es, fr, it, pt) | Cocinadas, Cuisinées, Cucinate, Feitas | Hechas, Faites, Fatte | Follows "Marcar como cocinada" etc. Natives may prefer the plainer "made" word for a recipe; it would change the mark's label too. |
| `recipes_empty_in_list` (all five) | "…con su marcador" and its equivalents | Tap the bookmark to save one to a list | The bookmark's name (*marcador*, *marque-page*, *Lesezeichen*, *segnalibro*) should be the one the platform's own bookmark glyph is called. |
| `recipes_cull_note`, `backup_skipped` | "se conservan hasta 50", "conservées jusqu'à 50", "bleiben bis zu 50 erhalten", "conservate fino a 50", "mantidas até 50" | "hasta un máximo de 50"… | Mirrors the English's "kept up to 50"; a native may want "a maximum of". Kept. |

**Recipes filter chips and the grid toggle (#378, #380, 2026-11-19, drafts)**

| Key | Current | Question for a native ear |
|---|---|---|
| `recipes_quick_pantry` | es "Con lo que hay en mi despensa", fr "Avec mon garde-manger", de "Mit meinem Vorrat", it "Con la mia dispensa", pt "Com o que tenho na despensa" | The English "Uses what's in my pantry" is a chip, so short; do these read as "recipes I can mostly make from it"? |
| `recipes_quick_not_cooked` | es "Aún sin cocinar", fr "Pas encore cuisinée", de "Noch nicht gekocht", it "Non ancora cucinata", pt "Ainda não feita" | Agrees with the library view's "cooked" word (`recipes_view_cooked`: Cocinadas, Cuisinées, Gekocht, Cucinate, Feitas); follows it if that changes. |
| `recipes_quick_30_or_less` | "30 min o menos", "30 min ou moins", "Bis 30 Min.", "30 min o meno", "30 min ou menos" | 30 minutes itself counts, as the English "30 min or less" (German "Bis 30 Min." is the idiomatic chip form). |
| `recipes_quick_pantry_note` | "…al menos la mitad de los ingredientes… por nombre, no por cantidad" and its equivalents | The honest statement of the rule (half, by name, not by amount): check it stays that plain. |
| `recipes_pantry_have` | "Tienes 6 de 8", "Vous avez 6 sur 8", "Du hast 6 von 8", "Hai 6 su 8", "Você tem 6 de 8" | The count says "of" the ingredients without the noun, as the English "You have 6 of 8". |

**Open questions**

- Italian: "piano" or "programma" for the meal plan (above).
- Spanish: the Groceries tab, "Compras" or "Compra" (above).
- Portuguese: `aisle_baking`, and the iOS photo library's name (above).
- The library views' notes and labels (the 2026-11-18 table above): the clip wording, French "Saisies à la main", and whether recipes are "cooked" or "made".
- The sample recipe's beans: the Spanish, French, Italian and Portuguese samples say 800 g of
  cooked beans, drained, which is about three tins against the English two cans. German now
  writes "2 Dosen weiße Bohnen (je 400 g)", as German recipes do.

## Long words

German was checked on iOS at the default size and the largest accessibility size. Text
wraps rather than truncating; two places needed a change: cook mode's top bar stacks
Exit above "Schritt 1 von 2" at the accessibility sizes, and a one-word screen title
("Einstellungen") shrinks to fit its line instead of breaking mid-word.

## Not translated yet

- **The seeded list names** (Favorites, Lunch, Dinner, Desserts, Breakfast, Snacks) are
  rows in the database, written in English when it's created, and a user can rename them.
  Showing them translated needs a rule for "not renamed yet"; it's left for a follow-up.
  The seeded meal types (Breakfast, Lunch, Dinner, Snack) are the same. So a message never
  names one in the UI language: deleting a meal type says its meals move to Dinner by the
  name the Week shows (`delete_meal_type_body` takes it as a placeholder), not "Cena" or
  "Abendessen".
- **Times from the recipe** ("1h 30m") are built by the parser with English unit letters,
  and the recipe text itself is shown as the site wrote it. Reading recipes written in
  other languages is #12 and #14–#16.
- **Timer durations** ("Start 1 hr timer" → "Timer für 1 hr starten") come from
  `StepTimers` in English ("10 min", "1 hr"); "min" reads fine in all five, "hr" doesn't.

