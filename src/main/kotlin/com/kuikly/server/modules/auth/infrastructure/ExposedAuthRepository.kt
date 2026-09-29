package com.kuikly.server.modules.auth.infrastructure

import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.modules.auth.domain.AuthRepository
import com.kuikly.server.modules.auth.domain.RefreshRecord
import com.kuikly.server.modules.auth.domain.Session
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

class ExposedAuthRepository : AuthRepository {
    override fun takeLoginAttempt(key: String, now: Long): Boolean {
        repeat(3) {
            LoginAttempts.insertIgnore { it[LoginAttempts.key] = key; it[windowStartedAt] = now; it[attempts] = 0 }
            val row = LoginAttempts.selectAll().where { LoginAttempts.key eq key }.forUpdate().singleOrNull() ?: return@repeat
            val reset = now - row[LoginAttempts.windowStartedAt] >= 60_000
            val count = if (reset) 0 else row[LoginAttempts.attempts]
            if (count >= 5) return false
            LoginAttempts.update({ LoginAttempts.key eq key }) {
                it[attempts] = count + 1
                if (reset) it[windowStartedAt] = now
            }
            return true
        }
        throw BusinessException(FailureKind.UNAVAILABLE, "AUTH_BUSY", "认证服务繁忙，请稍后重试")
    }

    override fun createSession(session: Session, refreshDigest: String) {
        Sessions.insert {
            it[id] = session.id; it[userId] = session.userId; it[expiresAt] = session.expiresAt; it[revoked] = false
        }
        RefreshTokens.insert { it[digest] = refreshDigest; it[sessionId] = session.id }
    }

    override fun findRefresh(digest: String): RefreshRecord? =
        RefreshTokens.selectAll().where { RefreshTokens.digest eq digest }.singleOrNull()?.refresh()

    override fun findSession(id: String, lock: Boolean): Session? {
        val query = Sessions.selectAll().where { Sessions.id eq id }
        if (lock) query.forUpdate()
        return query.singleOrNull()?.session()
    }

    override fun rotate(previous: RefreshRecord, nextDigest: String, requestId: String, now: Long, retryUntil: Long, ciphertext: String?) {
        val changed = RefreshTokens.update({ (RefreshTokens.digest eq previous.digest) and RefreshTokens.consumedAt.isNull() }) {
            it[consumedAt] = now; it[RefreshTokens.requestId] = requestId; it[successorDigest] = nextDigest
            it[RefreshTokens.retryUntil] = retryUntil; it[retryCiphertext] = ciphertext
        }
        check(changed == 1) { "Refresh rotation requires an unused token under the session lock" }
        RefreshTokens.insert { it[digest] = nextDigest; it[sessionId] = previous.sessionId }
    }

    override fun revokeSession(id: String) {
        Sessions.update({ Sessions.id eq id }) { it[revoked] = true }
        RefreshTokens.update({ RefreshTokens.sessionId eq id }) { it[retryCiphertext] = null }
    }

    override fun revokeAll(userId: String) {
        Sessions.selectAll().where { Sessions.userId eq userId }.map { it[Sessions.id] }.sorted().forEach(::revokeSession)
    }

    override fun cleanupCandidates(now: Long): List<Session> {
        val expired = Sessions.selectAll().where { Sessions.expiresAt lessEq now }.limit(100).map { it.session() }
        val retrySessions = RefreshTokens.select(RefreshTokens.sessionId)
            .where { (RefreshTokens.retryUntil lessEq now) and RefreshTokens.retryCiphertext.isNotNull() }
            .withDistinct().limit(100).map { it[RefreshTokens.sessionId] }
        val retries = if (retrySessions.isEmpty()) emptyList() else
            Sessions.selectAll().where { Sessions.id inList retrySessions }.map { it.session() }
        return (expired + retries).distinctBy { it.id }
    }

    override fun pruneSession(id: String, now: Long) {
        Sessions.deleteWhere { (Sessions.id eq id) and (expiresAt lessEq now) }
        RefreshTokens.update({ (RefreshTokens.sessionId eq id) and (RefreshTokens.retryUntil lessEq now) and RefreshTokens.retryCiphertext.isNotNull() }) {
            it[retryCiphertext] = null
        }
    }

    override fun pruneLoginAttempts(now: Long) {
        LoginAttempts.deleteWhere { windowStartedAt lessEq now - 60_000 }
    }
}

private fun ResultRow.session() = Session(this[Sessions.id], this[Sessions.userId], this[Sessions.expiresAt], this[Sessions.revoked])

private object Sessions : Table("app.auth_sessions") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36)
    val expiresAt = long("expires_at")
    val revoked = bool("revoked")
    override val primaryKey = PrimaryKey(id)
}

private object RefreshTokens : Table("app.refresh_tokens") {
    val digest = varchar("digest", 64)
    val sessionId = varchar("session_id", 36)
    val consumedAt = long("consumed_at").nullable()
    val requestId = varchar("request_id", 36).nullable()
    val successorDigest = varchar("successor_digest", 64).nullable()
    val retryUntil = long("retry_until").nullable()
    val retryCiphertext = text("retry_ciphertext").nullable()
    override val primaryKey = PrimaryKey(digest)
}

private object LoginAttempts : Table("app.login_attempts") {
    val key = varchar("key", 64)
    val windowStartedAt = long("window_started_at")
    val attempts = integer("attempts")
    override val primaryKey = PrimaryKey(key)
}

private fun ResultRow.refresh() = RefreshRecord(this[RefreshTokens.digest], this[RefreshTokens.sessionId],
    this[RefreshTokens.consumedAt], this[RefreshTokens.requestId], this[RefreshTokens.successorDigest],
    this[RefreshTokens.retryUntil], this[RefreshTokens.retryCiphertext])
