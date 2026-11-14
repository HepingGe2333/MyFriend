package com.myfriend.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkGuardTest {
    @Test
    fun rejectsHttpByDefault() {
        assertThrows(ApiException::class.java) {
            NetworkGuard.parseHttpUrl("http://example.com/v1", allowInsecureHttp = false)
        }
    }

    @Test
    fun acceptsHttpWhenExplicitlyAllowed() {
        val url = NetworkGuard.parseHttpUrl("http://192.168.1.8:8787/v1", allowInsecureHttp = true)
        assertEquals("http", url.scheme)
    }

    @Test
    fun appendsV1WhenMissing() {
        val endpoint = NetworkGuard.endpoint("https://api.example.com", "chat/completions")
        assertEquals("https://api.example.com/v1/chat/completions", endpoint.toString())
    }

    @Test
    fun preservesExistingV1() {
        val endpoint = NetworkGuard.endpoint("https://api.example.com/v1/", "models")
        assertEquals("https://api.example.com/v1/models", endpoint.toString())
    }
}
