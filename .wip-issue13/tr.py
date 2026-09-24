# -*- coding: utf-8 -*-
"""Translation table for issue #13. One row per string, keyed by the Android resource name.

S(name, en, es, fr, de, it, pt, ios=...) — a plain string.
P(name, {lang: {quantity: text}}, ios=...) — a plural.

`ios` is the String Catalog key (None = Android only). Android-only overrides go in
`android={lang: text}`, iOS-only in `iosv={lang: text}`. iOS values are derived from the
Android ones: %1$s -> %1$@, %1$d / %d -> %lld, and runs of spaces collapse to one
(Android never rendered them either).
"""

NB = " "  # French non-breaking space before : ; ? ! and inside guillemets

LANGS = ["es", "fr", "de", "it", "pt"]

ROWS = []


def S(name, en, es, fr, de, it, pt, ios="=", android=None, iosv=None, comment=None):
    ROWS.append(dict(kind="s", name=name, ios=(name if ios == "=" else ios),
                     v=dict(en=en, es=es, fr=fr, de=de, it=it, pt=pt),
                     android=android or {}, iosv=iosv or {}, comment=comment))


def P(name, v, ios="=", comment=None):
    ROWS.append(dict(kind="p", name=name, ios=(name if ios == "=" else ios), v=v,
                     android={}, iosv={}, comment=comment))


def fq(o, x):
    return f"«{NB}{x}{NB}»"


# Quote helpers: how each language quotes a recipe or list title.
Q = dict(
    en=lambda x: f'"{x}"',
    es=lambda x: f"«{x}»",
    fr=lambda x: f"«{NB}{x}{NB}»",
    de=lambda x: f"„{x}“",
    it=lambda x: f"«{x}»",
    pt=lambda x: f"“{x}”",
)

# ---- Common actions
S("action_back", "‹  Back", "‹  Atrás", "‹  Retour", "‹  Zurück", "‹  Indietro", "‹  Voltar", ios=None)
S("action_exit", "✕  Exit", "✕  Salir", "✕  Quitter", "✕  Beenden", "✕  Esci", "✕  Sair")
S("action_try_again", "Try again", "Reintentar", "Réessayer", "Erneut versuchen", "Riprova", "Tentar novamente")
S("action_start_cooking", "Start cooking", "Empezar a cocinar", "Commencer à cuisiner", "Jetzt kochen", "Inizia a cucinare", "Começar a cozinhar")
S("action_cancel", "Cancel", "Cancelar", "Annuler", "Abbrechen", "Annulla", "Cancelar")
S("action_delete", "Delete", "Eliminar", "Supprimer", "Löschen", "Elimina", "Excluir")
S("action_undo", "Undo", "Deshacer", "Annuler", "Rückgängig", "Annulla", "Desfazer")
S("action_pause", "Pause", "Pausar", "Pause", "Pause", "Pausa", "Pausar")
S("action_resume", "Resume", "Reanudar", "Reprendre", "Fortsetzen", "Riprendi", "Retomar")
S("action_reset", "Reset", "Reiniciar", "Réinitialiser", "Zurücksetzen", "Azzera", "Zerar")
S("action_go", "Go", "Ir", "OK", "Los", "Vai", "Ir")

# ---- Recipe screen
S("cd_share_recipe", "Share recipe", "Compartir receta", "Partager la recette", "Rezept teilen", "Condividi la ricetta", "Compartilhar receita")
S("cd_more_options", "More options", "Más opciones", "Plus d'options", "Weitere Optionen", "Altre opzioni", "Mais opções")
S("delete_recipe_title", 'Delete "%1$s"?', f"¿Eliminar {Q['es']('%1$s')}?", f"Supprimer {Q['fr']('%1$s')}{NB}?",
  f"{Q['de']('%1$s')} löschen?", f"Eliminare {Q['it']('%1$s')}?", f"Excluir {Q['pt']('%1$s')}?")
S("delete_recipe_body", "This removes it from history and any lists it's in. This can't be undone.",
  "La receta se eliminará del historial y de todas las listas en las que esté. No se puede deshacer.",
  "La recette sera retirée de l'historique et de toutes les listes où elle figure. Cette action est irréversible.",
  "Das Rezept wird aus dem Verlauf und allen Listen entfernt. Das lässt sich nicht rückgängig machen.",
  "La ricetta verrà rimossa dalla cronologia e da tutte le liste in cui si trova. L'operazione non si può annullare.",
  "A receita será removida do histórico e de todas as listas em que estiver. Isso não pode ser desfeito.")
S("label_prep", "Prep", "Preparación", "Préparation", "Vorbereitung", "Preparazione", "Preparo")
S("label_cook", "Cook", "Cocción", "Cuisson", "Garzeit", "Cottura", "Cozimento")
S("label_total", "Total", "Total", "Total", "Gesamt", "Totale", "Total")
S("heading_ingredients", "Ingredients", "Ingredientes", "Ingrédients", "Zutaten", "Ingredienti", "Ingredientes")
S("heading_instructions", "Instructions", "Elaboración", "Préparation", "Zubereitung", "Procedimento", "Modo de preparo")

# ---- Errors
S("error_no_recipe_found",
  "Couldn't find recipe data on this page. Some sites don't tag their recipes in a way this app can read yet.",
  "No se han encontrado datos de receta en esta página. Algunos sitios aún no marcan sus recetas de una forma que esta app pueda leer.",
  "Aucune donnée de recette trouvée sur cette page. Certains sites ne balisent pas encore leurs recettes d'une façon que cette appli sait lire.",
  "Auf dieser Seite wurden keine Rezeptdaten gefunden. Manche Seiten kennzeichnen ihre Rezepte noch nicht so, dass diese App sie lesen kann.",
  "Nessun dato della ricetta trovato in questa pagina. Alcuni siti non contrassegnano ancora le ricette in un modo che questa app sa leggere.",
  "Não encontramos dados de receita nesta página. Alguns sites ainda não marcam as receitas de um jeito que este app consiga ler.")
S("error_fetch_failed", "Couldn't load that page (%1$s).", "No se ha podido cargar la página (%1$s).",
  "Impossible de charger cette page (%1$s).", "Die Seite konnte nicht geladen werden (%1$s).",
  "Impossibile caricare la pagina (%1$s).", "Não foi possível carregar a página (%1$s).")
S("error_blocked",
  "The site didn't let the app in (HTTP %1$d). Sites often do this for a moment — try again in a minute.",
  "El sitio ha bloqueado la app (HTTP %1$d). Suele ser algo pasajero: vuelve a intentarlo en un minuto.",
  f"Le site a bloqué l'appli (HTTP %1$d). C'est souvent passager{NB}: réessayez dans une minute.",
  "Die Seite hat die App blockiert (HTTP %1$d). Das ist oft nur vorübergehend – versuch es in einer Minute noch einmal.",
  "Il sito ha bloccato l'app (HTTP %1$d). Spesso è una cosa passeggera: riprova tra un minuto.",
  "O site bloqueou o app (HTTP %1$d). Isso costuma passar rápido: tente de novo em um minuto.")
S("error_offline", "You're offline. The recipe will load when you're back online.",
  "No tienes conexión. La receta se cargará cuando vuelvas a estar en línea.",
  "Vous êtes hors ligne. La recette se chargera dès que vous serez de nouveau connecté.",
  "Du bist offline. Das Rezept wird geladen, sobald du wieder online bist.",
  "Sei offline. La ricetta verrà caricata quando tornerai online.",
  "Você está offline. A receita será carregada quando você voltar a ficar online.")
S("error_fetch_failed_unknown_detail", "unknown error", "error desconocido", "erreur inconnue", "unbekannter Fehler", "errore sconosciuto", "erro desconhecido")
S("error_save_failed", "Couldn't save that recipe.", "No se ha podido guardar la receta.", "Impossible d'enregistrer cette recette.",
  "Das Rezept konnte nicht gespeichert werden.", "Impossibile salvare la ricetta.", "Não foi possível salvar a receita.")
S("error_not_saved", "That recipe is no longer saved.", "Esa receta ya no está guardada.", "Cette recette n'est plus enregistrée.",
  "Dieses Rezept ist nicht mehr gespeichert.", "Questa ricetta non è più salvata.", "Essa receita não está mais salva.")
S("error_nothing_to_show", "There's no recipe to show.", "No hay ninguna receta que mostrar.", "Aucune recette à afficher.",
  "Es gibt kein Rezept zum Anzeigen.", "Nessuna ricetta da mostrare.", "Não há receita para mostrar.")
S("error_invalid_url", "That doesn't look like a link.", "Eso no parece un enlace.", "Cela ne ressemble pas à un lien.",
  "Das sieht nicht nach einem Link aus.", "Non sembra un link.", "Isso não parece um link.")

# ---- Serves / units row
S("label_serves", "Serves", "Raciones", "Portions", "Portionen", "Porzioni", "Porções")
S("label_makes", "Makes", "Rinde", "Quantité", "Ergibt", "Resa", "Rende")
S("cd_decrease_servings", "Decrease servings", "Reducir raciones", "Réduire le nombre de portions", "Portionen verringern", "Diminuisci le porzioni", "Diminuir porções")
S("cd_increase_servings", "Increase servings", "Aumentar raciones", "Augmenter le nombre de portions", "Portionen erhöhen", "Aumenta le porzioni", "Aumentar porções")
S("cd_decrease_amount", "Decrease amount", "Reducir cantidad", "Réduire la quantité", "Menge verringern", "Diminuisci la quantità", "Diminuir quantidade")
S("cd_increase_amount", "Increase amount", "Aumentar cantidad", "Augmenter la quantité", "Menge erhöhen", "Aumenta la quantità", "Aumentar quantidade")
S("original_servings", "Original: %1$s", "Original: %1$s", f"Original{NB}: %1$s", "Original: %1$s", "Originale: %1$s", "Original: %1$s")
S("cd_change_units", "Change units", "Cambiar unidades", "Changer d'unités", "Einheiten ändern", "Cambia unità", "Alterar unidades")
S("units_menu_header", "Units  ·   every recipe", "Unidades  ·   todas las recetas", "Unités  ·   toutes les recettes",
  "Einheiten  ·   alle Rezepte", "Unità  ·   tutte le ricette", "Unidades  ·   todas as receitas")
S("unit_as_written", "As written", "Como en la receta", "Comme dans la recette", "Wie im Rezept", "Come nella ricetta", "Como na receita")
S("unit_grams", "Grams", "Gramos", "Grammes", "Gramm", "Grammi", "Gramas")
S("unit_ounces", "Ounces", "Onzas", "Onces", "Unzen", "Once", "Onças")
S("unit_metric", "Metric", "Métrico", "Métrique", "Metrisch", "Metrico", "Métrico")
S("unit_as_written_description", "Exactly the units the recipe uses", "Exactamente las unidades de la receta",
  "Exactement les unités de la recette", "Genau die Einheiten aus dem Rezept", "Esattamente le unità della ricetta",
  "Exatamente as unidades da receita")
S("unit_grams_description", "Cups and spoons in grams", "Tazas y cucharadas en gramos", "Tasses et cuillères en grammes",
  "Tassen und Löffel in Gramm", "Tazze e cucchiai in grammi", "Xícaras e colheres em gramas")
S("unit_ounces_description", "Cups and spoons in ounces and pounds", "Tazas y cucharadas en onzas y libras",
  "Tasses et cuillères en onces et livres", "Tassen und Löffel in Unzen und Pfund", "Tazze e cucchiai in once e libbre",
  "Xícaras e colheres em onças e libras")
S("unit_metric_description", "Grams and millilitres", "Gramos y mililitros", "Grammes et millilitres", "Gramm und Milliliter",
  "Grammi e millilitri", "Gramas e mililitros")
S("convert_liquids_title", "Also convert liquids", "Convertir también los líquidos", "Convertir aussi les liquides",
  "Auch Flüssigkeiten umrechnen", "Converti anche i liquidi", "Converter também os líquidos")
S("convert_liquids_description", "Milk, water, oil and other pourables", "Leche, agua, aceite y otros líquidos",
  "Lait, eau, huile et autres liquides", "Milch, Wasser, Öl und andere Flüssigkeiten", "Latte, acqua, olio e altri liquidi",
  "Leite, água, óleo e outros líquidos")

P("servings", dict(
    en=dict(one="%1$d serving", other="%1$d servings"),
    es=dict(one="%1$d ración", many="%1$d de raciones", other="%1$d raciones"),
    fr=dict(one="%1$d portion", many="%1$d de portions", other="%1$d portions"),
    de=dict(one="%1$d Portion", other="%1$d Portionen"),
    it=dict(one="%1$d porzione", many="%1$d di porzioni", other="%1$d porzioni"),
    pt=dict(one="%1$d porção", many="%1$d de porções", other="%1$d porções")))

# ---- Cook view
S("cook_step_label", "STEP %1$d", "PASO %1$d", "ÉTAPE %1$d", "SCHRITT %1$d", "PASSAGGIO %1$d", "PASSO %1$d")
S("cook_position", "Step %1$d of %2$d", "Paso %1$d de %2$d", "Étape %1$d sur %2$d", "Schritt %1$d von %2$d",
  "Passaggio %1$d di %2$d", "Passo %1$d de %2$d")
S("cook_done_next", "Done — next step", "Hecho — siguiente paso", "Terminé — étape suivante", "Fertig – nächster Schritt",
  "Fatto — passaggio successivo", "Feito — próximo passo")
S("cook_done_finish", "Done — finish", "Hecho — terminar", "Terminé — finir", "Fertig – beenden", "Fatto — fine", "Feito — concluir")
S("cd_hide_ingredients", "Hide ingredients", "Ocultar ingredientes", "Masquer les ingrédients", "Zutaten ausblenden",
  "Nascondi gli ingredienti", "Ocultar ingredientes")
S("cd_show_ingredients", "Show ingredients", "Mostrar ingredientes", "Afficher les ingrédients", "Zutaten einblenden",
  "Mostra gli ingredienti", "Mostrar ingredientes")
S("cd_go_to_step", "Go to step %1$d", "Ir al paso %1$d", "Aller à l'étape %1$d", "Zu Schritt %1$d", "Vai al passaggio %1$d",
  "Ir para o passo %1$d")
S("timer_start", "⏱  Start %1$s timer", "⏱  Temporizador de %1$s", "⏱  Minuteur de %1$s", "⏱  Timer für %1$s starten",
  "⏱  Avvia timer di %1$s", "⏱  Timer de %1$s")
S("timers_up", "Time's up", "Se acabó el tiempo", "Temps écoulé", "Zeit ist um", "Tempo scaduto", "O tempo acabou")

P("cook_items", dict(
    en=dict(one="%1$d item", other="%1$d items"),
    es=dict(one="%1$d ingrediente", many="%1$d de ingredientes", other="%1$d ingredientes"),
    fr=dict(one="%1$d ingrédient", many="%1$d d'ingrédients", other="%1$d ingrédients"),
    de=dict(one="%1$d Zutat", other="%1$d Zutaten"),
    it=dict(one="%1$d ingrediente", many="%1$d di ingredienti", other="%1$d ingredienti"),
    pt=dict(one="%1$d ingrediente", many="%1$d de ingredientes", other="%1$d ingredientes")),
  ios="cook_items", comment="iOS only: the ingredient count on its own line in cook mode")

# ---- Home
S("home_subtitle", "Share a recipe link to this app from your browser, or paste one here.",
  "Comparte el enlace de una receta con esta app desde tu navegador, o pégalo aquí.",
  "Partagez le lien d'une recette avec cette appli depuis votre navigateur, ou collez-le ici.",
  "Teile einen Rezeptlink aus deinem Browser mit dieser App oder füge ihn hier ein.",
  "Condividi il link di una ricetta con questa app dal browser, oppure incollalo qui.",
  "Compartilhe o link de uma receita com este app pelo navegador ou cole aqui.")
S("label_recipe_url", "Recipe URL", "URL de la receta", "URL de la recette", "Rezept-URL", "URL della ricetta", "URL da receita")
S("section_continue_cooking", "Continue cooking", "Seguir cocinando", "Reprendre la recette", "Weiterkochen", "Continua a cucinare", "Continuar cozinhando")
S("section_recently_viewed", "Recently viewed", "Vistas recientemente", "Consultées récemment", "Zuletzt angesehen", "Viste di recente", "Vistas recentemente")
S("home_empty_hint", "Recipes you open will show up here.", "Las recetas que abras aparecerán aquí.",
  "Les recettes que vous ouvrez apparaîtront ici.", "Rezepte, die du öffnest, erscheinen hier.",
  "Le ricette che apri compariranno qui.", "As receitas que você abrir vão aparecer aqui.")
S("nav_history", "History", "Historial", "Historique", "Verlauf", "Cronologia", "Histórico")

# ---- History
S("history_title", "History", "Historial", "Historique", "Verlauf", "Cronologia", "Histórico")
S("history_empty", "Nothing yet. Recipes you open are kept here automatically.",
  "Aún no hay nada. Las recetas que abras se guardan aquí automáticamente.",
  "Rien pour l'instant. Les recettes que vous ouvrez sont conservées ici automatiquement.",
  "Noch nichts da. Rezepte, die du öffnest, landen automatisch hier.",
  "Ancora niente. Le ricette che apri vengono conservate qui automaticamente.",
  "Nada por aqui ainda. As receitas que você abrir ficam guardadas aqui automaticamente.")
S("history_no_results", 'No recipes match "%1$s".', f"Ninguna receta coincide con {Q['es']('%1$s')}.",
  f"Aucune recette ne correspond à {Q['fr']('%1$s')}.", f"Keine Rezepte zu {Q['de']('%1$s')} gefunden.",
  f"Nessuna ricetta corrisponde a {Q['it']('%1$s')}.", f"Nenhuma receita corresponde a {Q['pt']('%1$s')}.")
S("label_search_history", "Search titles and ingredients", "Buscar títulos e ingredientes", "Rechercher titres et ingrédients",
  "Titel und Zutaten durchsuchen", "Cerca titoli e ingredienti", "Buscar títulos e ingredientes")
S("cd_clear_search", "Clear search", "Borrar búsqueda", "Effacer la recherche", "Suche löschen", "Cancella la ricerca", "Limpar busca")
S("snackbar_deleted_one", 'Deleted "%1$s"', f"{Q['es']('%1$s')} eliminada", f"{Q['fr']('%1$s')} supprimée",
  f"{Q['de']('%1$s')} gelöscht", f"{Q['it']('%1$s')} eliminata", f"{Q['pt']('%1$s')} excluída")
P("snackbar_deleted_many", dict(
    en=dict(one="%1$d recipe deleted", other="%1$d recipes deleted"),
    es=dict(one="%1$d receta eliminada", many="%1$d de recetas eliminadas", other="%1$d recetas eliminadas"),
    fr=dict(one="%1$d recette supprimée", many="%1$d de recettes supprimées", other="%1$d recettes supprimées"),
    de=dict(one="%1$d Rezept gelöscht", other="%1$d Rezepte gelöscht"),
    it=dict(one="%1$d ricetta eliminata", many="%1$d di ricette eliminate", other="%1$d ricette eliminate"),
    pt=dict(one="%1$d receita excluída", many="%1$d de receitas excluídas", other="%1$d receitas excluídas")))

# ---- Shared
S("tag_saved", "Saved", "Guardada", "Enregistrée", "Gespeichert", "Salvata", "Salva")

# ---- Relative times
S("time_just_now", "Just now", "Ahora mismo", "À l'instant", "Gerade eben", "Proprio ora", "Agora mesmo")
S("time_yesterday", "Yesterday", "Ayer", "Hier", "Gestern", "Ieri", "Ontem")
S("time_date_format", "MMM d", "d MMM", "d MMM", "d. MMM", "d MMM", "d 'de' MMM", ios=None)
P("time_minutes_ago", dict(
    en=dict(one="%d min ago", other="%d min ago"),
    es=dict(one="hace %d min", many="hace %d min", other="hace %d min"),
    fr=dict(one="il y a %d min", many="il y a %d min", other="il y a %d min"),
    de=dict(one="vor %d Min.", other="vor %d Min."),
    it=dict(one="%d min fa", many="%d min fa", other="%d min fa"),
    pt=dict(one="há %d min", many="há %d min", other="há %d min")))
P("time_hours_ago", dict(
    en=dict(one="%d h ago", other="%d h ago"),
    es=dict(one="hace %d h", many="hace %d h", other="hace %d h"),
    fr=dict(one="il y a %d h", many="il y a %d h", other="il y a %d h"),
    de=dict(one="vor %d Std.", other="vor %d Std."),
    it=dict(one="%d h fa", many="%d h fa", other="%d h fa"),
    pt=dict(one="há %d h", many="há %d h", other="há %d h")))
P("time_days_ago", dict(
    en=dict(one="%d day ago", other="%d days ago"),
    es=dict(one="hace %d día", many="hace %d de días", other="hace %d días"),
    fr=dict(one="il y a %d jour", many="il y a %d de jours", other="il y a %d jours"),
    de=dict(one="vor %d Tag", other="vor %d Tagen"),
    it=dict(one="%d giorno fa", many="%d di giorni fa", other="%d giorni fa"),
    pt=dict(one="há %d dia", many="há %d de dias", other="há %d dias")))

S("dark_while_cooking_title", "Dark while cooking", "Oscuro al cocinar", "Sombre en cuisine", "Dunkel beim Kochen",
  "Scuro in cucina", "Escuro ao cozinhar")
S("dark_while_cooking_description", "Keep cook mode on an ink screen in light mode",
  "Modo cocina con fondo oscuro también en modo claro", "Mode cuisine sur fond sombre, même en mode clair",
  "Kochmodus auch im hellen Modus dunkel anzeigen", "Modalità cucina su sfondo scuro anche in modalità chiara",
  "Modo cozinhar com fundo escuro mesmo no modo claro")

# ---- Settings
SETTINGS = dict(es="Ajustes", de="Einstellungen", it="Impostazioni")
S("nav_settings", "Settings", "Ajustes", "Paramètres", "Einstellungen", "Impostazioni", "Configurações",
  iosv=dict(fr="Réglages", pt="Ajustes"))
S("settings_title", "Settings", "Ajustes", "Paramètres", "Einstellungen", "Impostazioni", "Configurações",
  iosv=dict(fr="Réglages", pt="Ajustes"))
S("settings_section_units", "Units", "Unidades", "Unités", "Einheiten", "Unità", "Unidades")
S("settings_section_oven_temperature", "Oven temperature", "Temperatura del horno", "Température du four", "Ofentemperatur",
  "Temperatura del forno", "Temperatura do forno")
S("settings_section_appearance", "Appearance", "Apariencia", "Apparence", "Darstellung", "Aspetto", "Aparência")
S("temperature_as_written", "As written", "Como en la receta", "Comme dans la recette", "Wie im Rezept", "Come nella ricetta", "Como na receita")
S("temperature_as_written_description", "Exactly the temperature the recipe uses", "Exactamente la temperatura de la receta",
  "Exactement la température de la recette", "Genau die Temperatur aus dem Rezept", "Esattamente la temperatura della ricetta",
  "Exatamente a temperatura da receita")
S("temperature_celsius", "Celsius (°C)", "Celsius (°C)", "Celsius (°C)", "Celsius (°C)", "Celsius (°C)", "Celsius (°C)")
S("temperature_celsius_description", "Oven temperatures converted to °C", "Temperaturas del horno convertidas a °C",
  "Températures du four converties en °C", "Ofentemperaturen in °C umgerechnet", "Temperature del forno convertite in °C",
  "Temperaturas do forno convertidas para °C")
S("temperature_fahrenheit", "Fahrenheit (°F)", "Fahrenheit (°F)", "Fahrenheit (°F)", "Fahrenheit (°F)", "Fahrenheit (°F)", "Fahrenheit (°F)")
S("temperature_fahrenheit_description", "Oven temperatures converted to °F", "Temperaturas del horno convertidas a °F",
  "Températures du four converties en °F", "Ofentemperaturen in °F umgerechnet", "Temperature del forno convertite in °F",
  "Temperaturas do forno convertidas para °F")

# ---- Lists
S("nav_lists", "Lists", "Listas", "Listes", "Listen", "Liste", "Listas")
S("lists_title", "Lists", "Listas", "Listes", "Listen", "Liste", "Listas")
S("action_new_list", "+  New list", "+  Nueva lista", "+  Nouvelle liste", "+  Neue Liste", "+  Nuova lista", "+  Nova lista")
S("action_create", "Create", "Crear", "Créer", "Erstellen", "Crea", "Criar")
S("action_rename", "Rename", "Cambiar nombre", "Renommer", "Umbenennen", "Rinomina", "Renomear")
S("action_save", "Save", "Guardar", "Enregistrer", "Speichern", "Salva", "Salvar")
S("label_list_name", "List name", "Nombre de la lista", "Nom de la liste", "Listenname", "Nome della lista", "Nome da lista")
S("list_empty", "Nothing in this list yet.", "Aún no hay nada en esta lista.", "Rien dans cette liste pour l'instant.",
  "Diese Liste ist noch leer.", "Ancora niente in questa lista.", "Nada nesta lista ainda.")
S("list_count_empty", "Empty", "Vacía", "Vide", "Leer", "Vuota", "Vazia")
P("list_count", dict(
    en=dict(one="%1$d recipe", other="%1$d recipes"),
    es=dict(one="%1$d receta", many="%1$d de recetas", other="%1$d recetas"),
    fr=dict(one="%1$d recette", many="%1$d de recettes", other="%1$d recettes"),
    de=dict(one="%1$d Rezept", other="%1$d Rezepte"),
    it=dict(one="%1$d ricetta", many="%1$d di ricette", other="%1$d ricette"),
    pt=dict(one="%1$d receita", many="%1$d de receitas", other="%1$d receitas")))

# ---- Save-to-list sheet
S("save_to_list_title", "Save to", "Guardar en", "Enregistrer dans", "Speichern in", "Salva in", "Salvar em")
S("cd_save_to_list", "Save to a list", "Guardar en una lista", "Enregistrer dans une liste", "In einer Liste speichern",
  "Salva in una lista", "Salvar em uma lista")
S("cd_in_a_list", "Saved to a list", "Guardada en una lista", "Enregistrée dans une liste", "In einer Liste gespeichert",
  "Salvata in una lista", "Salva em uma lista")

# ---- List detail
S("rename_list_title", "Rename list", "Cambiar nombre de la lista", "Renommer la liste", "Liste umbenennen", "Rinomina la lista", "Renomear lista")
S("delete_list_title", 'Delete "%1$s"?', f"¿Eliminar {Q['es']('%1$s')}?", f"Supprimer {Q['fr']('%1$s')}{NB}?",
  f"{Q['de']('%1$s')} löschen?", f"Eliminare {Q['it']('%1$s')}?", f"Excluir {Q['pt']('%1$s')}?")
S("delete_list_body", "The list is removed. The recipes in it stay in your history.",
  "Se eliminará la lista. Sus recetas seguirán en tu historial.",
  "La liste sera supprimée. Ses recettes resteront dans votre historique.",
  "Die Liste wird entfernt. Die Rezepte darin bleiben in deinem Verlauf.",
  "La lista verrà rimossa. Le sue ricette resteranno nella cronologia.",
  "A lista será removida. As receitas dela continuam no seu histórico.")
S("action_delete_list", "Delete list", "Eliminar lista", "Supprimer la liste", "Liste löschen", "Elimina la lista", "Excluir lista")

# ---- Share text (the plain-text message body; labels passed into RecipeShareText)
S("share_serves", "Serves %1$d", "Raciones: %1$d", f"Portions{NB}: %1$d", "Portionen: %1$d", "Porzioni: %1$d", "Porções: %1$d")
S("share_makes", "Makes %1$d", "Rinde: %1$d", f"Quantité{NB}: %1$d", "Ergibt %1$d", "Resa: %1$d", "Rende %1$d")
S("share_scaled", "%1$s (originally %2$d)", "%1$s (originalmente %2$d)", "%1$s (à l'origine %2$d)", "%1$s (ursprünglich %2$d)",
  "%1$s (in origine %2$d)", "%1$s (originalmente %2$d)")
S("share_heading_ingredients", "INGREDIENTS", "INGREDIENTES", "INGRÉDIENTS", "ZUTATEN", "INGREDIENTI", "INGREDIENTES")
S("share_heading_instructions", "INSTRUCTIONS", "ELABORACIÓN", "PRÉPARATION", "ZUBEREITUNG", "PROCEDIMENTO", "MODO DE PREPARO")
