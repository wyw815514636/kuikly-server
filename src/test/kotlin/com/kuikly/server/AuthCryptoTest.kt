package com.kuikly.server

import com.kuikly.server.config.AuthSettings
import com.kuikly.server.modules.auth.infrastructure.PasswordCodec
import com.kuikly.server.modules.auth.infrastructure.TokenCodec
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthCryptoTest {
    @Test
    fun `secrets are mandatory independent and redacted`() {
        assertFailsWith<IllegalArgumentException> { AuthSettings.load(MapApplicationConfig()) }
        val config = authConfig()
        val settings = AuthSettings.load(config)
        assertFalse(settings.toString().contains(config.property("auth.signingKey").getString()))
        config.put("auth.retryKey", config.property("auth.signingKey").getString())
        assertFailsWith<IllegalArgumentException> { AuthSettings.load(config) }
        config.put("auth.signingKey", "short")
        assertFailsWith<IllegalArgumentException> { AuthSettings.load(config) }
    }

    @Test
    fun `password hashes have random salts and verify without truncation`() = runBlocking {
        val passwords = PasswordCodec()
        val password = "a-long-valid-password-中文-12345"
        val first = passwords.hash(password)
        assertNotEquals(first, passwords.hash(password))
        assertTrue(passwords.verify(password, first))
        assertFalse(passwords.verify(password + "x", first))
        assertFalse(passwords.verify(password, "invalid"))
        assertFalse(first.contains(password))
    }

    @Test
    fun `retry encryption is randomized authenticated and bound to token digest`() {
        val codec = TokenCodec(AuthSettings.load(authConfig()))
        val first = codec.seal("credentials", "digest-a")
        assertNotEquals(first, codec.seal("credentials", "digest-a"))
        assertEquals("credentials", codec.open(first, "digest-a"))
        assertFailsWith<Exception> { codec.open(first, "digest-b") }
        assertFailsWith<Exception> { codec.open(first.dropLast(4) + "AAAA", "digest-a") }
        assertEquals(43, codec.newRefreshToken().length)
        assertNotEquals(codec.newRefreshToken(), codec.newRefreshToken())
    }
}

internal fun authConfig() = MapApplicationConfig(
    "auth.signingKey" to Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
    "auth.retryKey" to Base64.getEncoder().encodeToString(ByteArray(32) { (it + 33).toByte() }),
)
