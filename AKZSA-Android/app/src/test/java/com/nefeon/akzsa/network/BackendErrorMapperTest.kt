package com.nefeon.akzsa.network

import org.junit.Assert.assertTrue
import org.junit.Test

class BackendErrorMapperTest {
    @Test fun mapsRequiredHttpErrorsClearly() {
        assertTrue(BackendErrorMapper.messageFor(401).contains("token"))
        assertTrue(BackendErrorMapper.messageFor(422).contains("validation"))
        assertTrue(BackendErrorMapper.messageFor(403).contains("denied"))
        assertTrue(BackendErrorMapper.messageFor(404).contains("not found"))
        assertTrue(BackendErrorMapper.messageFor(429).contains("rate-limiting"))
        assertTrue(BackendErrorMapper.messageFor(500).contains("internal error"))
        assertTrue(BackendErrorMapper.messageFor(503).contains("not ready"))
    }
}
