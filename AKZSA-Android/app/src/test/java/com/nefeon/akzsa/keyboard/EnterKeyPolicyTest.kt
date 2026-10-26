package com.nefeon.akzsa.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterKeyPolicyTest {
    @Test fun multilineTextAlwaysGetsNewlineEvenWhenHostDefinesDoneAction() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val options = EditorInfo.IME_ACTION_DONE
        assertEquals(EnterKeyPolicy.Behavior.INSERT_NEWLINE, EnterKeyPolicy.decide(inputType, options))
    }

    @Test fun searchFieldPerformsSearchAction() {
        assertEquals(
            EnterKeyPolicy.Behavior.PERFORM_EDITOR_ACTION,
            EnterKeyPolicy.decide(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH),
        )
    }

    @Test fun simpleSingleLineFieldSendsEnterToHost() {
        assertEquals(
            EnterKeyPolicy.Behavior.SEND_ENTER_KEY,
            EnterKeyPolicy.decide(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }
}
