package com.kuikly.server

import com.kuikly.server.config.ServerConfig
import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerConfigTest {
    @Test
    fun `loads bounded database settings without revealing secrets`() {
        val settings = ServerConfig.load(validConfig())
        assertEquals(4, settings.database.maximumPoolSize)
        assertEquals(3_000L, settings.database.connectionTimeoutMillis)
        assertEquals(5, settings.database.queryTimeoutSeconds)
        assertFalse(settings.toString().contains("secret-probe"))
        assertFalse(settings.database.toString().contains("secret-probe"))
    }

    @Test
    fun `rejects missing credentials and invalid database limits`() {
        for ((key, value) in listOf(
            "database.password" to "",
            "database.username" to "",
            "database.jdbcUrl" to "jdbc:h2:mem:unsupported",
            "database.maximumPoolSize" to "0",
            "database.maximumPoolSize" to "1000",
            "database.connectionTimeoutMillis" to "0",
            "database.queryTimeoutSeconds" to "0",
            "database.queryTimeoutSeconds" to "not-a-number",
            "database.migrationTimeoutSeconds" to "0",
            "database.migrationTimeoutSeconds" to "3601",
            "app.environment" to "production-typo",
        )) {
            val config = validConfig().apply { put(key, value) }
            val failure = assertFailsWith<IllegalArgumentException>(key) { ServerConfig.load(config) }
            assertFalse(failure.message.orEmpty().contains("secret-probe"))
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.load(MapApplicationConfig()) }
    }
}

internal fun validConfig() = MapApplicationConfig(
    "auth.signingKey" to authConfig().property("auth.signingKey").getString(),
    "auth.retryKey" to authConfig().property("auth.retryKey").getString(),
    "app.environment" to "test",
    "database.jdbcUrl" to "jdbc:postgresql://localhost:5432/kuikly",
    "database.username" to "kuikly",
    "database.password" to "secret-probe",
    "database.maximumPoolSize" to "4",
    "database.connectionTimeoutMillis" to "3000",
    "database.queryTimeoutSeconds" to "5",
)
