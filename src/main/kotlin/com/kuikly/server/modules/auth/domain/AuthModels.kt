package com.kuikly.server.modules.auth.domain

data class Session(val id: String, val userId: String, val expiresAt: Long, val revoked: Boolean)

data class RefreshRecord(
    val digest: String,
    val sessionId: String,
    val consumedAt: Long?,
    val requestId: String?,
    val successorDigest: String?,
    val retryUntil: Long?,
    val retryCiphertext: String?,
)

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long, val sessionId: String) {
    override fun toString(): String = "TokenPair(credentials=<redacted>, sessionId=$sessionId)"
}

interface AuthRepository {
    fun takeLoginAttempt(key: String, now: Long): Boolean
    fun createSession(session: Session, refreshDigest: String)
    fun findRefresh(digest: String): RefreshRecord?
    fun findSession(id: String, lock: Boolean = false): Session?
    fun rotate(previous: RefreshRecord, nextDigest: String, requestId: String, now: Long, retryUntil: Long, ciphertext: String?)
    fun revokeSession(id: String)
    fun revokeAll(userId: String)
    fun cleanupCandidates(now: Long): List<Session>
    fun pruneSession(id: String, now: Long)
    fun pruneLoginAttempts(now: Long)
}

interface Passwords {
    suspend fun hash(password: String): String
    suspend fun verify(password: String, encoded: String?): Boolean
}

interface Tokens {
    fun newId(): String
    fun newRefreshToken(): String
    fun digest(value: String): String
    fun issue(userId: String, sessionId: String, refreshToken: String, now: Long, sessionExpiresAt: Long): TokenPair
    fun encrypt(pair: TokenPair, digest: String): String
    fun decrypt(ciphertext: String, digest: String): TokenPair
}
