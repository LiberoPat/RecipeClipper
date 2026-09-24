import Foundation

/// Trimmed Reddit `.json` listings: Reddit's shapes, made-up posts. The same text as Android's
/// `RedditFixtures.kt` (copied from it, not rewritten), so both suites test the same input.
enum RedditFixtures {
    static let selfPost = #"""
[
{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
  "subreddit":"recipes","title":"Weeknight Lemon Chicken Orzo","is_self":true,
  "selftext":"My go-to when I have no plan. Story time is over, here it is.\n\nServes 4\n\nPrep time: 10 min\n\nCook time: 25 minutes\n\n**Ingredients**\n\n* 1 lb chicken thighs\n* 1 cup orzo\n* 2 1/2 cups chicken broth\n* 1 lemon, zested and juiced\n\n**Instructions**\n\n1. Brown the chicken in a deep pan, 6 minutes a side.\n2. Add the orzo and broth and simmer for 12 minutes.\n3. Stir in the lemon and serve.\n\n**Notes**\n\nThighs stay juicier than breasts.\n\nEdit: thanks for the award!",
  "url":"https://www.reddit.com/r/recipes/comments/1abc01/weeknight_lemon_chicken_orzo/",
  "preview":{"images":[{"source":{"url":"https://preview.redd.it/orzo.jpeg?width=1080&amp;format=pjpg&amp;s=abc","width":1080,"height":1350},"resolutions":[]}],"enabled":false}
}}]}},
{"kind":"Listing","data":{"children":[]}}
]
"""#

    static let cardWithTranscription = #"""
[
{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
  "subreddit":"Old_Recipes","title":"Grandma's date nut bread, found in her tin","is_self":false,
  "selftext":"","post_hint":"image",
  "url_overridden_by_dest":"https://i.redd.it/card01.jpeg","url":"https://i.redd.it/card01.jpeg",
  "preview":{"images":[{"source":{"url":"https://preview.redd.it/card01.jpeg?auto=webp&amp;s=def","width":3024,"height":4032}}]}
}}]}},
{"kind":"Listing","data":{"children":[
  {"kind":"t1","data":{"author":"AutoModerator","stickied":true,"distinguished":"moderator","body":"Thank you for posting to r/Old_Recipes! Please remember to add a transcription.","replies":""}},
  {"kind":"t1","data":{"author":"reader_one","body":"Her handwriting is beautiful.","replies":""}},
  {"kind":"t1","data":{"author":"reader_two","body":"Could someone transcribe this? I can't read the second half.","replies":{"kind":"Listing","data":{"children":[
    {"kind":"t1","data":{"author":"helper","is_submitter":false,"body":"Transcription:\n\n**Ingredients**\n\n- 1 cup chopped dates\n- 1 tsp baking soda\n- 1 cup boiling water\n- 1 3/4 cups flour\n- 1/2 cup chopped walnuts\n\n**Directions**\n\n1. Pour the boiling water over the dates and soda and let cool.\n2. Stir in the flour and nuts.\n3. Bake in a greased loaf pan at 350°F for 1 hour.","replies":""}},
    {"kind":"more","data":{"count":2,"children":["k5","k6"]}}
  ]}}}},
  {"kind":"t1","data":{"author":"reader_three","body":"Ingredients:\n\n1 cup dates\n1 cup water\nflour?\nwalnuts","replies":""}},
  {"kind":"more","data":{"count":4,"children":["k7","k8"]}}
]}}
]
"""#

    static let photoOnly = #"""
[
{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
  "subreddit":"food","title":"[Homemade] Sunday lasagna","is_self":false,"selftext":"",
  "is_gallery":true,"url":"https://www.reddit.com/gallery/1abc03",
  "gallery_data":{"items":[{"media_id":"m1","id":1},{"media_id":"m2","id":2}]},
  "media_metadata":{
    "m2":{"status":"valid","e":"Image","m":"image/jpg","s":{"y":1000,"x":800,"u":"https://preview.redd.it/m2.jpg?width=800&amp;s=2"}},
    "m1":{"status":"valid","e":"Image","m":"image/jpg","s":{"y":1000,"x":800,"u":"https://preview.redd.it/m1.jpg?width=800&amp;s=1"}}
  }
}}]}},
{"kind":"Listing","data":{"children":[
  {"kind":"t1","data":{"author":"reader_one","body":"Recipe? That looks incredible.","replies":{"kind":"Listing","data":{"children":[
    {"kind":"t1","data":{"author":"op","is_submitter":true,"body":"Just my mum's recipe, lots of cheese and a slow sauce!","replies":""}}
  ]}}}},
  {"kind":"t1","data":{"author":"reader_two","body":"[deleted]","replies":""}}
]}}
]
"""#

    static let crosspost = #"""
[
{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
  "subreddit":"Cooking","title":"Crossposting this great soup","is_self":false,"selftext":"",
  "url":"/r/soup/comments/1abc04/tomato_soup/",
  "crosspost_parent_list":[{"subreddit":"soup","title":"Tomato soup","is_self":true,
    "selftext":"## Ingredients\n\n* 2 lb tomatoes\n* 1 onion\n\n## Method\n\n* Roast everything.\n* Blend.",
    "preview":{"images":[{"source":{"url":"https://preview.redd.it/soup.jpeg?a=1&amp;s=x"}}]}}]
}}]}},
{"kind":"Listing","data":{"children":[]}}
]
"""#

}
