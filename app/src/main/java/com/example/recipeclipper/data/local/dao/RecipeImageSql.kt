package com.example.recipeclipper.data.local.dao

/**
 * The recipe picture the list queries return as `imageUrl` (#386): `photo://<photoFile>` followed by
 * `#` and the recipe's web link when it has both (so the loader can show the link when the file is
 * missing), else the plain link. The same string as [com.example.recipeclipper.data.model.StoredPhoto.model]:
 * the link's own fragment is cut (it never matters to a picture, and a second `#` would end it early).
 * Annotation arguments must be constants, so the two spellings of the column names are written out.
 */
internal object RecipeImageSql {
    /** For a query over `recipes` with no alias. */
    const val PLAIN =
        "CASE WHEN photoFile IS NULL THEN imageUrl ELSE 'photo://' || photoFile || " +
            "COALESCE('#' || NULLIF(substr(imageUrl, 1, instr(imageUrl || '#', '#') - 1), ''), '') END"

    /** For a query over `recipes r`. */
    const val AS_R =
        "CASE WHEN r.photoFile IS NULL THEN r.imageUrl ELSE 'photo://' || r.photoFile || " +
            "COALESCE('#' || NULLIF(substr(r.imageUrl, 1, instr(r.imageUrl || '#', '#') - 1), ''), '') END"
}
