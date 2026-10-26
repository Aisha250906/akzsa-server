package com.nefeon.akzsa.keyboard

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Bottom inset handling for the IME window (gesture bar / three-button nav / display cutouts).
 * Insets are runtime inputs: nothing here assumes a particular manufacturer or navigation mode.
 */
object KeyboardInsets {

    /** Returns the system navigation/gesture bottom inset in px, or 0 when none applies. */
    fun bottomInsetPx(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }

    fun leftInsetPx(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.navigationBars()).left
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetLeft
        }

    fun rightInsetPx(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.navigationBars()).right
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetRight
        }

    /**
     * Pads [root] so keys never sit under system bars. [basePadding] is the renderer's own padding in px
     * (left, top, right, bottom). Re-applies automatically when insets change (rotation, fold, nav mode).
     */
    fun install(root: View, basePadding: IntArray) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                basePadding[0] + leftInsetPx(insets),
                basePadding[1],
                basePadding[2] + rightInsetPx(insets),
                basePadding[3] + bottomInsetPx(insets),
            )
            insets
        }
        root.requestApplyInsets()
    }
}
