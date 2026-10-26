package com.nefeon.akzsa.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidationTest {
    @Test fun acceptsProductionHttpsBaseUrlAndNormalizesTrailingSlash() {
        val result = SettingsValidation.validateBackendBaseUrl("https://akzsa-server.onrender.com/")
        assertTrue(result.valid)
        assertEquals("https://akzsa-server.onrender.com", result.normalized)
    }

    @Test fun rejectsInsecureRemoteHttp() {
        assertFalse(SettingsValidation.validateBackendBaseUrl("http://example.com").valid)
    }

    @Test fun acceptsHttpForAndroidEmulatorLoopbackOnly() {
        assertTrue(SettingsValidation.validateBackendBaseUrl("http://10.0.2.2:8000").valid)
        assertTrue(SettingsValidation.validateBackendBaseUrl("http://localhost:8000").valid)
        assertFalse(SettingsValidation.validateBackendBaseUrl("http://192.168.1.5:8000").valid)
    }

    @Test fun rejectsCredentialsQueryAndFragment() {
        assertFalse(SettingsValidation.validateBackendBaseUrl("https://user:pass@example.com").valid)
        assertFalse(SettingsValidation.validateBackendBaseUrl("https://example.com?x=1").valid)
        assertFalse(SettingsValidation.validateBackendBaseUrl("https://example.com#route").valid)
    }
}
