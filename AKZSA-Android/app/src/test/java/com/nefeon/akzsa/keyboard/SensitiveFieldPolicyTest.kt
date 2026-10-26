package com.nefeon.akzsa.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveFieldPolicyTest {
    private fun field(inputType: Int, ime: Int = 0, hint: String? = null, priv: String? = null) =
        EditorFieldInfo(inputType, ime, hint, priv)

    @Test fun textPasswordVariationsAreProtected() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ).forEach { v ->
            assertEquals(FieldSensitivity.PASSWORD, SensitiveFieldPolicy.classify(field(InputType.TYPE_CLASS_TEXT or v)))
        }
    }

    @Test fun numericPasswordIsProtected() {
        val f = field(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertEquals(FieldSensitivity.PASSWORD, SensitiveFieldPolicy.classify(f))
    }

    @Test fun incognitoFlagIsProtected() {
        val f = field(InputType.TYPE_CLASS_TEXT, ime = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(FieldSensitivity.INCOGNITO, SensitiveFieldPolicy.classify(f))
        assertTrue(SensitiveFieldPolicy.isSensitive(f))
    }

    @Test fun otpAndPinHintsAreProtected() {
        listOf("Enter OTP", "One-time code", "Verification code", "6-digit PIN", "CVV", "Security code", "Enter passcode", "ओटीपी दर्ज करें")
            .forEach { hint ->
                val f = field(InputType.TYPE_CLASS_NUMBER, hint = hint)
                assertEquals("hint '$hint'", FieldSensitivity.SECRET_CODE, SensitiveFieldPolicy.classify(f))
            }
    }

    @Test fun privateImeOptionsCanMarkSecretFields() {
        assertEquals(FieldSensitivity.SECRET_CODE, SensitiveFieldPolicy.classify(field(InputType.TYPE_CLASS_TEXT, priv = "otp")))
    }

    @Test fun ordinaryFieldsAreNotProtected() {
        listOf(
            field(InputType.TYPE_CLASS_TEXT),
            field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            field(InputType.TYPE_CLASS_TEXT, hint = "Search"),
            field(InputType.TYPE_CLASS_NUMBER, hint = "Quantity"),
            field(InputType.TYPE_CLASS_PHONE),
        ).forEach { assertFalse(SensitiveFieldPolicy.isSensitive(it)) }
    }

    @Test fun hintWordsAreMatchedAsWholeWords() {
        // "spinning" contains "pin", "shipping" contains "pin" -- must not trigger.
        assertFalse(SensitiveFieldPolicy.isSensitive(field(InputType.TYPE_CLASS_TEXT, hint = "Shipping address")))
        assertFalse(SensitiveFieldPolicy.isSensitive(field(InputType.TYPE_CLASS_TEXT, hint = "Spinning class notes")))
    }

    @Test fun statusMessagesAreDistinctAndNeverEmpty() {
        val msgs = FieldSensitivity.values().map { SensitiveFieldPolicy.statusMessage(it) }
        assertEquals(msgs.size, msgs.toSet().size)
        assertTrue(msgs.all { it.isNotBlank() })
    }
}
