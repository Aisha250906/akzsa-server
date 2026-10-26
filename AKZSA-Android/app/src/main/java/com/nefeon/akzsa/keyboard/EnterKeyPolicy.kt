package com.nefeon.akzsa.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Centralizes Enter-key behavior so multiline editors always receive a newline. */
internal object EnterKeyPolicy {
    enum class Behavior { INSERT_NEWLINE, PERFORM_EDITOR_ACTION, SEND_ENTER_KEY }

    fun decide(inputType: Int, imeOptions: Int): Behavior {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val isMultilineText = inputClass == InputType.TYPE_CLASS_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (isMultilineText) return Behavior.INSERT_NEWLINE

        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS -> Behavior.PERFORM_EDITOR_ACTION
            else -> Behavior.SEND_ENTER_KEY
        }
    }
}
