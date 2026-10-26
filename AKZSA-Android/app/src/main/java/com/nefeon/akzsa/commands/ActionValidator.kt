package com.nefeon.akzsa.commands

import com.nefeon.akzsa.network.CommandAction

enum class PlanKind { DICTATION, INSERT_GENERATED_TEXT, DELETE_LAST_WORDS, DELETE_LAST_CHARACTERS, DELETE_ALL_TEXT, NEW_PARAGRAPH, REPLACE_SELECTED_TEXT, FIND_FILE }

data class ActionPlan(
    val kind: PlanKind,
    val explanation: String,
    val requiresConfirmation: Boolean,
    val count: Int? = null,
    val replacementText: String? = null,
    val sourceSelection: String? = null,
)

sealed interface ValidationResult {
    data class Accepted(val plan: ActionPlan) : ValidationResult
    data class Rejected(val message: String) : ValidationResult
}

/** Client-side allowlist. Never dispatch a backend-supplied string as executable behavior. */
object ActionValidator {
    private const val MAX_DICTATION_CHARS = 2_000
    private const val MAX_REPLACEMENT_CHARS = 10_000
    private const val MAX_DELETE_WORDS = 50
    private const val MAX_DELETE_CHARS = 100

    fun validate(action: CommandAction, transcript: String, selectedText: String?): ValidationResult {
        val explanation = action.explanation.trim().take(300).ifBlank { "Review this action before continuing." }
        return when (action.kind) {
            "dictation" -> {
                val text = action.text?.takeIf { it.isNotBlank() } ?: transcript.takeIf { it.isNotBlank() }
                if (text.isNullOrBlank()) reject("The backend did not provide any text to insert.")
                else if (text.length > MAX_DICTATION_CHARS) reject("The proposed text is too long to insert in one operation.")
                else ValidationResult.Accepted(ActionPlan(PlanKind.DICTATION, "Insert dictated text", action.requires_confirmation, replacementText = text))
            }
            "generate_text" -> {
                val text = action.text?.takeIf { it.isNotBlank() }
                    ?: return reject("The writing backend returned no generated text.")
                if (text.length > MAX_REPLACEMENT_CHARS) reject("The generated text is too long to insert safely.")
                else if (!selectedText.isNullOrBlank()) reject("Text is selected. Ask AKZSA to rewrite the selection, or clear the selection before generating new text.")
                else ValidationResult.Accepted(ActionPlan(PlanKind.INSERT_GENERATED_TEXT, "Insert AI-generated text", true, replacementText = text))
            }
            "delete_all_text" -> ValidationResult.Accepted(
                ActionPlan(PlanKind.DELETE_ALL_TEXT, "Clear all text in the currently focused field", true)
            )
            "delete_last_words" -> {
                val count = action.count ?: return reject("The backend omitted the word count; no text was changed.")
                if (count !in 1..MAX_DELETE_WORDS) reject("Word deletion is limited to 1–$MAX_DELETE_WORDS words per action.")
                else ValidationResult.Accepted(ActionPlan(PlanKind.DELETE_LAST_WORDS, "Delete the last $count words at the end of the text field", true, count = count))
            }
            "delete_last_characters" -> {
                val count = action.count ?: return reject("The backend omitted the character count; no text was changed.")
                if (count !in 1..MAX_DELETE_CHARS) reject("Character deletion is limited to 1–$MAX_DELETE_CHARS characters per action.")
                else ValidationResult.Accepted(ActionPlan(PlanKind.DELETE_LAST_CHARACTERS, "Delete the last $count characters at the end of the text field", true, count = count))
            }
            "new_paragraph" -> ValidationResult.Accepted(ActionPlan(PlanKind.NEW_PARAGRAPH, "Start a new paragraph", action.requires_confirmation))
            "correct_text", "rewrite_text", "translate_text" -> {
                val selected = selectedText?.takeIf { it.isNotBlank() }
                    ?: return reject("Select the text first and enable the explicit editor-context sharing setting to use this edit.")
                val replacement = action.text?.takeIf { it.isNotBlank() }
                    ?: return reject("The backend classified the edit but did not return replacement text. Nothing was changed.")
                if (replacement.length > MAX_REPLACEMENT_CHARS) reject("The proposed replacement is too long.")
                else if (replacement == selected) reject("The backend returned the same text, not an edited version. Nothing was changed.")
                else ValidationResult.Accepted(ActionPlan(PlanKind.REPLACE_SELECTED_TEXT, explanation, true, replacementText = replacement, sourceSelection = selected))
            }
            "undo" -> reject("Undo is not universally supported by Android text fields, so AKZSA did not change anything.")
            "find_file" -> {
                val query = action.query?.trim()?.takeIf { it.length >= 2 } ?: transcript.trim()
                if (query.length < 2 || query.length > 300) reject("Please say what file you want to find.")
                else ValidationResult.Accepted(ActionPlan(PlanKind.FIND_FILE, "Search files in an authorized folder", false, replacementText = query))
            }
            "unknown" -> reject("AKZSA could not interpret that command confidently. Try saying it another way.")
            else -> reject("This action type is not supported by AKZSA and was rejected.")
        }
    }

    private fun reject(message: String) = ValidationResult.Rejected(message)
}
