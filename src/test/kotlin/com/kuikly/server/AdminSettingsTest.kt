package com.kuikly.server

import com.kuikly.server.config.AdminSettings
import io.ktor.server.config.MapApplicationConfig
import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminSettingsTest {
    private fun config() = MapApplicationConfig(
        "admin.enabled" to "true",
        "admin.secretKey" to Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
        "admin.origins" to "https://admin.example.com",
        "app.environment" to "prod",
    )
    @Test fun `session policy is bounded and production origin must use https`() {
        val c = config()
        c.put("admin.idleSeconds", "600")
        c.put("admin.sessionSeconds", "3600")
        val settings = requireNotNull(AdminSettings.load(c))
        assertEquals(600_000L, settings.idleMillis)
        assertEquals(3_600_000L, settings.sessionMillis)
        c.put("admin.idleSeconds", "3601")
        assertThrows(IllegalArgumentException::class.java) { AdminSettings.load(c) }
        c.put("admin.idleSeconds", "600")
        c.put("admin.origins", "http://admin.example.com")
        assertThrows(IllegalArgumentException::class.java) { AdminSettings.load(c) }
    }
    @Test fun `admin encryption key cannot reuse client keys`() {
        val c = config()
        c.put("auth.signingKey", c.property("admin.secretKey").getString())
        assertThrows(IllegalArgumentException::class.java) { AdminSettings.load(c) }
    }
}
