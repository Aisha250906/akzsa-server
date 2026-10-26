package com.nefeon.akzsa.commands

import com.nefeon.akzsa.network.CommandAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionValidatorTest {
    @Test fun deleteWordsAlwaysRequiresConfirmationEvenWhenServerDoesNot() {
        val result = ActionValidator.validate(
            CommandAction(kind = "delete_last_words", count = 5, requires_confirmation = false, explanation = "Delete last five words"),
            "delete last five words", null,
        ) as ValidationResult.Accepted
        assertEquals(PlanKind.DELETE_LAST_WORDS, result.plan.kind)
        assertTrue(result.plan.requiresConfirmation)
        assertEquals(5, result.plan.count)
    }

    @Test fun excessiveWordDeletionIsRejected() {
        val result = ActionValidator.validate(CommandAction(kind = "delete_last_words", count = 501), "delete words", null)
        assertTrue(result is ValidationResult.Rejected)
    }

    @Test fun invalidCharacterCountIsRejected() {
        assertTrue(ActionValidator.validate(CommandAction(kind = "delete_last_characters", count = 0), "delete", null) is ValidationResult.Rejected)
        assertTrue(ActionValidator.validate(CommandAction(kind = "delete_last_characters", count = 101), "delete", null) is ValidationResult.Rejected)
    }

    @Test fun unknownActionIsRejected() {
        val result = ActionValidator.validate(CommandAction(kind = "launch_shell", text = "rm -rf"), "do it", null)
        assertTrue(result is ValidationResult.Rejected)
    }

    @Test fun undoIsExplicitlyUnsupported() {
        val result = ActionValidator.validate(CommandAction(kind = "undo"), "undo that", null)
        assertTrue(result is ValidationResult.Rejected)
        assertTrue((result as ValidationResult.Rejected).message.contains("not universally supported"))
    }

    @Test fun sameTextIsNotAcceptedAsRewrite() {
        val selected = "Please review this sentence."
        val result = ActionValidator.validate(
            CommandAction(kind = "rewrite_text", text = selected), "rewrite this professionally", selected,
        )
        assertTrue(result is ValidationResult.Rejected)
        assertTrue((result as ValidationResult.Rejected).message.contains("same text"))
    }

    @Test fun replacementAlwaysRequiresPreviewAndSelection() {
        val result = ActionValidator.validate(
            CommandAction(kind = "correct_text", text = "A corrected sentence.", requires_confirmation = false),
            "correct this", "a corrected sentence",
        ) as ValidationResult.Accepted
        assertTrue(result.plan.requiresConfirmation)
        assertEquals(PlanKind.REPLACE_SELECTED_TEXT, result.plan.kind)
        assertFalse(result.plan.replacementText.isNullOrBlank())
    }

    @Test fun replacementWithoutSelectionIsRejected() {
        assertTrue(ActionValidator.validate(
            CommandAction(kind = "translate_text", text = "Bonjour"), "translate this", null,
        ) is ValidationResult.Rejected)
    }

    @Test fun emptyDictationRejectedWhenTranscriptEmpty() {
        assertTrue(ActionValidator.validate(CommandAction(kind = "dictation", text = " "), "", null) is ValidationResult.Rejected)
    }

    @Test fun generatedTextRequiresPreviewAndIsAccepted() {
        val result = ActionValidator.validate(
            CommandAction(kind = "generate_text", text = "Hello, thanks for the update.", requires_confirmation = false),
            "Write a polite reply", null,
        ) as ValidationResult.Accepted
        assertEquals(PlanKind.INSERT_GENERATED_TEXT, result.plan.kind)
        assertTrue(result.plan.requiresConfirmation)
        assertEquals("Hello, thanks for the update.", result.plan.replacementText)
    }

    @Test fun generatedTextRejectsEmptyPayload() {
        assertTrue(ActionValidator.validate(CommandAction(kind = "generate_text", text = " "), "write a reply", null) is ValidationResult.Rejected)
    }

    @Test fun deleteAllTextAlwaysRequiresConfirmation() {
        val result = ActionValidator.validate(CommandAction(kind = "delete_all_text", requires_confirmation = false), "clear all text", null) as ValidationResult.Accepted
        assertEquals(PlanKind.DELETE_ALL_TEXT, result.plan.kind)
        assertTrue(result.plan.requiresConfirmation)
    }


    @Test
    fun findFileActionIsAcceptedAndCarriesQuery() {
        val action = CommandAction(kind = "find_file", query = "GST invoice for 25000", requires_confirmation = false, explanation = "Search files")
        val result = ActionValidator.validate(action, "Find my GST invoice for 25000", null)
        assertTrue(result is ValidationResult.Accepted)
        assertEquals(PlanKind.FIND_FILE, (result as ValidationResult.Accepted).plan.kind)
        assertEquals("GST invoice for 25000", result.plan.replacementText)
    }
}
