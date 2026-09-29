package com.kuikly.server.modules.auth.infrastructure

import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.modules.auth.domain.Passwords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PasswordCodec : Passwords {
    private val random = SecureRandom()
    private val dispatcher = Dispatchers.Default.limitedParallelism(2)
    private val capacity = Semaphore(8)
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private val dummy = "pbkdf2-sha256:600000:${encoder.encodeToString(ByteArray(16))}:${encoder.encodeToString(ByteArray(32))}"

    override suspend fun hash(password: String): String = bounded {
        require(password.length in 15..128) { "Password must contain 15 to 128 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        "pbkdf2-sha256:600000:${encoder.encodeToString(salt)}:${encoder.encodeToString(derive(password, salt))}"
    }

    override suspend fun verify(password: String, encoded: String?): Boolean = bounded {
        if (password.length !in 1..128) return@bounded false
        val parts = (encoded ?: dummy).split(':')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256" || parts[1] != "600000") return@bounded false
        val salt: ByteArray
        val expected: ByteArray
        try {
            salt = decoder.decode(parts[2])
            expected = decoder.decode(parts[3])
        } catch (_: IllegalArgumentException) { return@bounded false }
        if (salt.size != 16 || expected.size != 32) return@bounded false
        val matches = MessageDigest.isEqual(expected, derive(password, salt))
        matches && encoded != null
    }

    private suspend fun <T> bounded(block: () -> T): T {
        if (!capacity.tryAcquire()) throw BusinessException(FailureKind.UNAVAILABLE, "AUTH_BUSY", "认证服务繁忙，请稍后重试")
        return try { withContext(dispatcher) { block() } } finally { capacity.release() }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }
}
