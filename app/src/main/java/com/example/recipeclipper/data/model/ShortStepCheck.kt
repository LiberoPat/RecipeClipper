package com.example.recipeclipper.data.model

/**
 * Chef mode's gate (#100): whether a short version of a step, written by the on-device model,
 * may be shown in place of the step as written. Pure, and the same on iOS (the differential
 * corpus's `Short` rows pin it).
 *
 * The model writes words; code owns every number. A short version passes only if it is shorter
 * than the step, every number in it (fractions, decimal commas and each end of a range, as
 * written) appears in the step, and it states exactly the step's times and temperatures: none
 * changed, none added, none dropped ("350°F (180°C)" may keep either half). Anything else, and
 * any recipe language the app has no words for, shows the step as written.
 */
object ShortStepCheck {

    /** [short], tidied, when it may stand for [original]; null to show [original] as written. */
    fun accept(original: String, short: String?, words: LanguageWords?): String? {
        if (words == null || short == null) return null
        val candidate = tidy(short)
        if (candidate.isEmpty() || candidate.length >= tidy(original).length) return null
        if (!numbers(original).containsAll(numbers(candidate))) return null
        if (StepTimers.durations(original, words).toSet() != StepTimers.durations(candidate, words).toSet()) {
            return null
        }
        val stated = TemperatureConverter.temperatures(original, words)
        val kept = TemperatureConverter.temperatures(candidate, words).flatten().toSet()
        if (!stated.flatten().containsAll(kept) || stated.any { keys -> keys.none { it in kept } }) return null
        return candidate
    }

    /** A step this short ("Serve warm.") is left as written, never sent to the model. */
    fun worthShortening(step: String): Boolean = tidy(step).length >= MIN_LENGTH

    const val MIN_LENGTH = 40

    /** One line: trimmed, whitespace collapsed, a leading bullet and wrapping quotes removed. */
    fun tidy(text: String): String {
        var s = text.trim().replace(SPACES, " ")
        s = s.replace(BULLET, "")
        if (s.length >= 2 && s.first() in QUOTES && s.last() in QUOTES) s = s.substring(1, s.length - 1).trim()
        return s
    }

    /** Every number in [text], as written: "1,5", "1 1/2", "1½", "½"; a range's ends separately. */
    fun numbers(text: String): Set<String> {
        val plain = buildString {
            for (c in text) {
                append(
                    when (c) {
                        in '０'..'９' -> '0' + (c - '０')
                        '／', '⁄' -> '/'
                        else -> c
                    }
                )
            }
        }
        return NUMBER.findAll(plain).map { it.value.replace(SPACES, " ").replace(SPACED_FRACTION, "$1$2") }.toSet()
    }

    private const val FRACTIONS = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅐⅛⅜⅝⅞⅑⅒"
    private val NUMBER = Regex("""\d+(?:[.,]\d+)*(?:\s+\d+/\d+|/\d+|\s*[$FRACTIONS])?|[$FRACTIONS]""")
    private val SPACED_FRACTION = Regex("""(\d) ([$FRACTIONS])""")
    private val SPACES = Regex("""\s+""")
    private val BULLET = Regex("""^[-•*]\s+""")
    private const val QUOTES = "\"'“”„«»「」"
}
