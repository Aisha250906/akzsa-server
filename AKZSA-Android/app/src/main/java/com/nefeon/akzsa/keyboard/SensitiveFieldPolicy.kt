package com.nefeon.akzsa.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** What kind of protected field is focused. Anything except [NORMAL] disables voice, cloud, AI and history. */
enum class FieldSensitivity {
    NORMAL,
    /** Password / web password / visible password / numeric password (PIN) input types. */
    PASSWORD,
    /** The app marked the editor as private (incognito): `IME_FLAG_NO_PERSONALIZED_LEARNING`. */
    INCOGNITO,
    /** Heuristic: hint or IME options indicate an OTP, PIN, CVV, card or similar secret. */
    SECRET_CODE,
}

/** Minimal, Android-free view of an editor so the policy is unit-testable on a plain JVM. */
data class EditorFieldInfo(
    val inputType: Int,
    val imeOptions: Int = 0,
    val hintText: String? = null,
    val privateImeOptions: String? = null,
    val label: String? = null,
) {
    companion object {
        fun from(info: EditorInfo): EditorFieldInfo = EditorFieldInfo(
            inputType = info.inputType,
            imeOptions = info.imeOptions,
            hintText = info.hintText?.toString(),
            privateImeOptions = info.privateImeOptions,
            label = info.label?.toString(),
        )
    }
}

/**
 * Single place that decides whether the focused field is protected.
 *
 * Contract: a protected field must never start recording, never send text or context to the backend,
 * never run contextual AI edits, never open file search, and never be logged or kept in history.
 *
 * The type-based rules are authoritative. The hint-based rule is a best-effort heuristic (apps are not
 * required to label OTP fields); it errs on the side of protection, which only costs convenience.
 */
object SensitiveFieldPolicy {

    private val secretHint = Regex(
        "(?i)\\b(otp|one[- ]?time|verification code|security code|auth(entication)? code|passcode|pass code|" +
            "password|passphrase|pin|cvv|cvc|card number|ssn|2fa|mfa|recovery code|backup code|secret)\\b" +
            "|पासवर्ड|ओटीपी|ಪಾಸ್ವರ್ಡ್|ಒಟಿಪಿ",
    )

    fun classify(field: EditorFieldInfo): FieldSensitivity {
        val inputClass = field.inputType and InputType.TYPE_MASK_CLASS
        val variation = field.inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass == InputType.TYPE_CLASS_TEXT && variation in textPasswordVariations) return FieldSensitivity.PASSWORD
        if (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return FieldSensitivity.PASSWORD

        if (field.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return FieldSensitivity.INCOGNITO

        val signals = listOfNotNull(field.hintText, field.privateImeOptions, field.label)
        if (signals.any { secretHint.containsMatchIn(it) }) return FieldSensitivity.SECRET_CODE

        return FieldSensitivity.NORMAL
    }

    fun isSensitive(field: EditorFieldInfo): Boolean = classify(field) != FieldSensitivity.NORMAL

    /** Short, non-technical text for the keyboard status line. */
    fun statusMessage(kind: FieldSensitivity): String = when (kind) {
        FieldSensitivity.NORMAL -> "Ready to type"
        FieldSensitivity.PASSWORD -> "Voice and AI are off in password fields"
        FieldSensitivity.INCOGNITO -> "Voice and AI are off in private fields"
        FieldSensitivity.SECRET_CODE -> "Voice and AI are off in code and PIN fields"
    }

    private val textPasswordVariations = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
}
