package com.kuikly.server.config

import io.ktor.server.config.ApplicationConfig
import java.security.MessageDigest
import java.util.Base64

class AuthSettings private constructor(
    val signingKey: ByteArray,
    val retryKey: ByteArray,
    val issuer: String,
    val audience: String,
    val accessSeconds: Long,
    val sessionSeconds: Long,
    val graceSeconds: Long,
) {
    override fun toString(): String = "AuthSettings(keys=<redacted>, issuer=$issuer, audience=$audience)"

    companion object {
        fun load(config: ApplicationConfig): AuthSettings {
            fun key(name: String): ByteArray {
                val value = config.propertyOrNull(name)?.getString().orEmpty()
                val bytes = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) { byteArrayOf() }
                require(bytes.size == 32) { "$name must be a Base64 encoded 32-byte random key" }
                return bytes
            }
            fun seconds(name: String, default: Long, range: LongRange): Long {
                val value = config.propertyOrNull(name)?.getString()?.toLongOrNull() ?: run {
                    require(config.propertyOrNull(name) == null) { "$name must be an integer" }
                    default
                }
                require(value in range) { "$name is outside the permitted range" }
                return value
            }
            fun text(name: String, default: String): String =
                (config.propertyOrNull(name)?.getString() ?: default).also {
                    require(it.isNotBlank() && it.length <= 256) { "$name must be nonblank and at most 256 characters" }
                }
            val signing = key("auth.signingKey")
            val retry = key("auth.retryKey")
            require(!MessageDigest.isEqual(signing, retry)) { "Authentication keys must be independent" }
            return AuthSettings(
                signing, retry, text("auth.issuer", "kuikly-server"), text("auth.audience", "kuikly-client"),
                seconds("auth.accessSeconds", 900, 60L..1800L),
                seconds("auth.sessionSeconds", 2_592_000, 3600L..2_592_000L),
                seconds("auth.graceSeconds", 10, 0L..15L),
            )
        }
    }
}
