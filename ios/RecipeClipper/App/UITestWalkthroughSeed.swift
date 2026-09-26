#if DEBUG
import Foundation

/// The `walkthrough` scenario (#106): twenty realistic recipes, so the walkthrough videos show
/// a lived-in library and the free tier reads "20 of 20". Android's `WalkthroughSeed` holds the
/// same recipes. Viewed in this order, newest first. "Grandma's Lentil Soup" is stored as picked
/// from the page text by the model (#103); the Sponge Cake's first steps are the ones the stub
/// Chef mode model shortens (#100).
enum UITestWalkthroughSeed {
    struct Seed {
        let title: String
        let slug: String
        let servings: String
        let ingredients: [String]
        let steps: [String]
        var origin = "PARSED"
        var times: (prep: String?, cook: String?, total: String?) = (nil, nil, nil)
    }

    static let whisk = "Whisk the eggs and sugar in a large bowl until pale, thick and doubled in volume, about 8 minutes."
    static let whiskShort = "Whisk eggs and sugar until pale and thick, about 8 minutes."

    static let recipes: [Seed] = [
        Seed(title: "Chicken Adobo", slug: "chicken-adobo", servings: "4",
             ingredients: ["2 lb chicken thighs", "1/2 cup soy sauce", "1/3 cup white vinegar", "6 cloves garlic, crushed",
                           "1 onion, sliced", "3 bay leaves", "1 tsp black peppercorns"],
             steps: ["Put the chicken, soy sauce, vinegar, garlic, bay leaves and peppercorns in a pot and marinate for 30 minutes.",
                     "Bring to a boil, then simmer covered for 30 minutes.",
                     "Add the onion and simmer uncovered for 10 minutes, until the sauce thickens.",
                     "Serve with steamed rice."],
             times: ("35m", "40m", "1h 15m")),
        Seed(title: "Weeknight Chili", slug: "weeknight-chili", servings: "6",
             ingredients: ["1 lb beef", "1 onion, diced", "3 cloves garlic, minced", "2 tbsp chili powder",
                           "1 can (14 oz) diced tomatoes", "1 can (15 oz) kidney beans, drained", "1 cup beef broth"],
             steps: ["Brown the beef in a large pot.", "Add the onion and garlic and cook for 5 minutes.",
                     "Stir in the chili powder, tomatoes, beans and broth.", "Simmer for 20 minutes.", "Serve with rice."],
             times: ("10m", "30m", "40m")),
        Seed(title: "Spaghetti Carbonara", slug: "spaghetti-carbonara", servings: "4",
             ingredients: ["400 g spaghetti", "150 g guanciale, diced", "4 egg yolks", "1 egg",
                           "50 g pecorino romano, grated", "1 tsp black pepper"],
             steps: ["Cook the spaghetti in salted water for 9 minutes.", "Fry the guanciale until crisp.",
                     "Whisk the yolks, egg, pecorino and pepper.",
                     "Toss the pasta with the guanciale off the heat, then stir in the eggs with a splash of pasta water."]),
        Seed(title: "Sponge Cake", slug: "sponge-cake", servings: "8",
             ingredients: ["4 eggs", "1 cup sugar", "1 cup flour", "1 tsp vanilla extract", "2 tbsp butter, melted"],
             steps: [UITestSeeding.chefStep, whisk,
                     "Fold in the flour in three additions, then the vanilla and the butter.",
                     "Bake at 350°F for 25 minutes.", "Cool in the tin for 10 minutes before turning out."]),
        Seed(title: "Grandma's Lentil Soup", slug: "blog/lentil-soup-memories", servings: "6",
             ingredients: ["1 cup red lentils", "1 onion, chopped", "2 carrots, diced", "6 cups vegetable broth", "1 tsp cumin"],
             steps: ["Soften the onion and carrots in a little oil.", "Add the lentils, broth and cumin.",
                     "Simmer for 25 minutes, until the lentils fall apart."],
             origin: "EXTRACTED"),
        Seed(title: "Banana Bread", slug: "banana-bread", servings: "1 loaf",
             ingredients: ["3 ripe bananas", "2 cups flour", "1 tsp baking soda", "1/2 cup butter, melted",
                           "3/4 cup brown sugar", "2 eggs"],
             steps: ["Mash the bananas and stir in the butter, sugar and eggs.", "Fold in the flour and baking soda.",
                     "Bake at 350°F for 60 minutes."]),
        Seed(title: "Miso Soup", slug: "miso-soup", servings: "4",
             ingredients: ["4 cups dashi", "3 tbsp white miso paste", "1 block tofu, cubed", "2 green onions, sliced"],
             steps: ["Warm the dashi.", "Whisk in the miso off the boil.", "Add the tofu and green onions."])
    ] + fillers

    private static let fillers: [Seed] = [
        ("Shakshuka", ["1 onion, sliced", "2 red peppers, sliced", "1 can (28 oz) crushed tomatoes", "6 eggs"]),
        ("Greek Salad", ["4 tomatoes", "1 cucumber", "200 g feta", "1/2 cup kalamata olives"]),
        ("Pad Thai", ["200 g rice noodles", "2 eggs", "3 tbsp fish sauce", "1 cup bean sprouts"]),
        ("Mushroom Risotto", ["1 1/2 cups arborio rice", "500 g mushrooms", "6 cups chicken stock", "1/2 cup parmesan"]),
        ("Fish Tacos", ["1 lb white fish", "8 corn tortillas", "2 cups shredded cabbage", "1 lime"]),
        ("Chocolate Chip Cookies", ["2 1/4 cups flour", "1 cup butter", "3/4 cup sugar", "2 cups chocolate chips"]),
        ("Buttermilk Pancakes", ["2 cups flour", "2 cups buttermilk", "2 eggs", "2 tbsp sugar"]),
        ("Tomato Basil Soup", ["2 lb tomatoes", "1 onion, chopped", "4 cups vegetable broth", "1 cup basil leaves"]),
        ("Beef Stir-Fry", ["1 lb flank steak", "2 cups broccoli florets", "3 tbsp soy sauce", "1 tbsp cornstarch"]),
        ("Lemon Garlic Salmon", ["4 salmon fillets", "3 cloves garlic, minced", "1 lemon", "2 tbsp butter"]),
        ("Guacamole", ["3 avocados", "1 lime", "1/2 red onion, diced", "1 jalapeño"]),
        ("French Toast", ["8 slices bread", "4 eggs", "1 cup milk", "1 tsp cinnamon"]),
        ("Caprese Salad", ["3 tomatoes", "250 g fresh mozzarella", "1 cup basil leaves", "2 tbsp olive oil"])
    ].map { title, ingredients in
        Seed(title: title, slug: title.lowercased().replacingOccurrences(of: " ", with: "-"), servings: "4",
             ingredients: ingredients, steps: ["Prepare the ingredients.", "Cook and serve."])
    }

    static func seed(_ conn: SQLiteConnection, now: Int64) throws {
        let minute: Int64 = 60_000
        let dao = RecipeDao(db: conn)
        var ids: [String: Int64] = [:]
        for (i, r) in recipes.enumerated() {
            ids[r.title] = try dao.insert(RecipeRecord(
                sourceUrl: "https://example.com/\(r.slug)", title: r.title, imageUrl: nil,
                ingredients: r.ingredients, instructions: r.steps,
                prepTime: r.times.prep, cookTime: r.times.cook, totalTime: r.times.total, servings: r.servings,
                sourceType: SourceType.blog.rawValue, lastViewedAt: now - Int64(i + 1) * minute, contentOrigin: r.origin
            ))
        }
        // Every recipe is in a list, so none is removable and a new one meets the free tier's
        // "library full" prompt (#107) rather than replacing the oldest.
        let lists = ListDao(db: conn)
        let byName = Dictionary(uniqueKeysWithValues: try lists.lists(recipeId: ListDao.noRecipe).map { ($0.name, $0.id) })
        for (title, id) in ids {
            for list in (title == "Chicken Adobo" ? ["Favorites"] : []) + [listFor(title)] {
                guard let listId = byName[list] else { fatalError("UI test seed: no list \(list)") }
                try lists.addToList(ListMembership(recipeId: id, listId: listId, addedAt: now - 30 * minute))
            }
        }
    }

    static func listFor(_ title: String) -> String {
        switch title {
        case "Banana Bread", "Buttermilk Pancakes", "French Toast": "Breakfast"
        case "Sponge Cake", "Chocolate Chip Cookies": "Desserts"
        case "Greek Salad", "Caprese Salad", "Tomato Basil Soup", "Miso Soup", "Grandma's Lentil Soup": "Lunch"
        case "Guacamole": "Snacks"
        default: "Dinner"
        }
    }
}
#endif
