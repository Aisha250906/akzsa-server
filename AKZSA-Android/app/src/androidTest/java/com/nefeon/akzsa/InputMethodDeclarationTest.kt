package com.nefeon.akzsa

import android.content.ComponentName
import android.inputmethodservice.InputMethodService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nefeon.akzsa.keyboard.AkzsaInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side smoke test for the service declaration and class wiring. */
@RunWith(AndroidJUnit4::class)
class InputMethodDeclarationTest {
    @Test fun keyboardServiceIsDeclaredAndResolves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, AkzsaInputMethodService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(component, 0)
        assertEquals("android.permission.BIND_INPUT_METHOD", serviceInfo.permission)
        assertTrue(InputMethodService::class.java.isAssignableFrom(AkzsaInputMethodService::class.java))
    }
}
