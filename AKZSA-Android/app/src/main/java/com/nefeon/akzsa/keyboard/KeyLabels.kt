package com.nefeon.akzsa.keyboard

/** Screen-reader descriptions for keys whose visible label is a symbol or abbreviation. */
object KeyLabels {
    fun describe(label: String): String = when (label) {
        "⌫" -> "Delete"
        "⇧" -> "Shift"
        "⇪" -> "Caps lock on"
        "?123" -> "Switch to numbers and symbols"
        "ABC" -> "Switch to letters"
        "अ/A", "A/अ" -> "Switch between English and Hindi keys"
        "अआ" -> "Hindi vowels"
        "कख" -> "Hindi consonants"
        "space" -> "Space"
        "↵" -> "Enter"
        "Go" -> "Go"
        "Search" -> "Search"
        "Send" -> "Send"
        "Next" -> "Next field"
        "Done" -> "Done"
        "Prev" -> "Previous field"
        "🎙 Talk" -> "Start voice typing"
        "■ Stop" -> "Stop voice typing"
        "⏳ Processing" -> "Processing speech, please wait"
        "FILE", "FILE ✓" -> "Search files on this phone"
        "⚙" -> "Open AKZSA settings"
        "⌄" -> "Hide keyboard"
        "⌨" -> "Switch to another keyboard"
        "," -> "Comma"
        "." -> "Period"
        "।" -> "Hindi full stop"
        "्" -> "Halant"
        else -> label
    }
}
