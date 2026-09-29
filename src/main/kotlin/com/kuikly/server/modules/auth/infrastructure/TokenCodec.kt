package com.kuikly.server.modules.auth.infrastructure

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.kuikly.server.config.AuthSettings
import com.kuikly.server.modules.auth.domain.TokenPair
import com.kuikly.server.modules.auth.domain.Tokens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TokenCodec(private val settings: AuthSettings) : Tokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val algorithm = Algorithm.HMAC256(settings.signingKey)
    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(settings.issuer).withAudience(settings.audience)
        .withClaim("kind", "access").withClaimPresence("exp").withClaimPresence("iat")
        .withClaimPresence("sub").withClaimPresence("sid").build()

    override fun newId(): String = UUID.randomUUID().toString()
    override fun newRefreshToken(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
    override fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override fun issue(userId: String, sessionId: String, refreshToken: String, now: Long, sessionExpiresAt: Long): TokenPair {
        val expiresAt = minOf(now + settings.accessSeconds * 1000, sessionExpiresAt)
        val jwt = JWT.create().withIssuer(settings.issuer).withAudience(settings.audience)
            .withSubject(userId).withClaim("sid", sessionId).withClaim("kind", "access")
            .withJWTId(newId()).withIssuedAt(Instant.ofEpochMilli(now))
            .withExpiresAt(Instant.ofEpochMilli(expiresAt)).sign(algorithm)
        return TokenPair(jwt, refreshToken, (expiresAt / 1000 - now / 1000).coerceAtLeast(0), sessionId)
    }

    override fun encrypt(pair: TokenPair, digest: String): String = seal(
        Json.encodeToString(CachedPair(pair.accessToken, pair.refreshToken, pair.expiresIn, pair.sessionId)), digest,
    )

    override fun decrypt(ciphertext: String, digest: String): TokenPair =
        Json.decodeFromString<CachedPair>(open(ciphertext, digest)).let {
            TokenPair(it.accessToken, it.refreshToken, it.expiresIn, it.sessionId)
        }

    fun seal(plaintext: String, associatedData: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData)
        return encoder.encodeToString(nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun open(ciphertext: String, associatedData: String): String {
        val bytes = decoder.decode(ciphertext)
        require(bytes.size >= 28)
        return cipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(0, 12), associatedData)
            .doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun cipher(mode: Int, nonce: ByteArray, associatedData: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(settings.retryKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        }
}

@Serializable
private data class CachedPair(val accessToken: String, val refreshToken: String, val expiresIn: Long, val sessionId: String)
