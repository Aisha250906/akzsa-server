package com.nefeon.akzsa.keyboard

/**
 * Prepares recognised speech for insertion at the cursor. Pure and local: only the 1-2 characters in front
 * of the cursor are looked at (never stored or sent anywhere) so words do not glue together.
 *
 * Fixes the old behaviour where dictating after typed text produced "Hellohow are you" and where the first
 * word never got a capital letter in sentence-case fields.
 */
object DictationFormatter {
    /** Characters after which no space must be inserted (opening brackets/quotes, joiners). */
    private const val NO_SPACE_AFTER = "([{\"'“‘-/@#\n\t "
    /** Characters that must stay attached to the previous word. */
    private const val ATTACH_BEFORE = ",.;:!?।)]}%\n"

    fun format(
        transcript: String,
        textBeforeCursor: String,
        hasSelection: Boolean,
        /** False for e-mail / URL / numeric fields: never add spaces there. */
        spaceSafe: Boolean,
        appendTrailingSpace: Boolean,
        capitalizeFirst: Boolean,
    ): String {
        var text = transcript.trim(' ', '\t', '\u00A0').replace(Regex("[ \\t]{2,}"), " ")
        if (text.isEmpty()) return ""
        if (capitalizeFirst) {
            text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        val last = textBeforeCursor.lastOrNull()
        val needsLeadingSpace = spaceSafe && !hasSelection && last != null &&
            last !in NO_SPACE_AFTER && text.first() !in ATTACH_BEFORE
        if (needsLeadingSpace) text = " $text"
        if (appendTrailingSpace && spaceSafe && !text.endsWith(" ") && !text.endsWith("\n")) text += " "
        return text
    }

    /** True when the field wants the next letter capitalised (`InputConnection.getCursorCapsMode` != 0). */
    fun wantsCapital(capsMode: Int): Boolean = capsMode != 0
}
