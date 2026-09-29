package com.kuikly.server.modules.auth.application

import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.application.TransactionRunner
import com.kuikly.server.modules.auth.domain.AuthRepository
import com.kuikly.server.modules.auth.domain.Passwords
import com.kuikly.server.modules.auth.domain.Session
import com.kuikly.server.modules.auth.domain.TokenPair
import com.kuikly.server.modules.auth.domain.Tokens
import com.kuikly.server.modules.user.domain.User
import com.kuikly.server.modules.user.domain.UserRepository
import com.kuikly.server.modules.user.domain.UserRole
import com.kuikly.server.modules.user.application.UserProvisioner

class AuthService(
    private val transactions: TransactionRunner,
    private val users: UserRepository,
    private val sessions: AuthRepository,
    private val passwords: Passwords,
    private val tokens: Tokens,
    private val sessionMillis: Long,
    private val graceMillis: Long,
    private val now: () -> Long,
) {
    suspend fun createUser(username: String, password: String, role: UserRole): User =
        UserProvisioner(transactions, users, passwords, tokens::newId).create(username, password, role)

    suspend fun login(username: String, password: String): TokenPair {
        val allowed = transactions.execute { sessions.takeLoginAttempt(tokens.digest(username), now()) }
        if (!allowed) throw BusinessException(FailureKind.TOO_MANY_REQUESTS, "LOGIN_THROTTLED", "登录尝试过于频繁，请稍后重试")
        val candidate = transactions.execute { users.findByUsername(username) }
        val verified = passwords.verify(password, candidate?.passwordHash)
        if (!verified || candidate == null) invalidCredentials()
        return transactions.execute {
            val user = users.findById(candidate.id, lock = true)
            if (user == null || !user.enabled || user.passwordHash != candidate.passwordHash) invalidCredentials()
            val time = now()
            val session = Session(tokens.newId(), user.id, time + sessionMillis, false)
            val refresh = tokens.newRefreshToken()
            sessions.createSession(session, tokens.digest(refresh))
            tokens.issue(user.id, session.id, refresh, time, session.expiresAt)
        }
    }

    suspend fun refresh(refreshToken: String, requestId: String): TokenPair {
        val digest = tokens.digest(refreshToken)
        val outcome = transactions.execute {
            val hint = sessions.findRefresh(digest) ?: return@execute RefreshOutcome.Invalid
            val owner = sessions.findSession(hint.sessionId) ?: return@execute RefreshOutcome.Invalid
            val user = users.findById(owner.userId, lock = true) ?: return@execute RefreshOutcome.Invalid
            val session = sessions.findSession(hint.sessionId, lock = true) ?: return@execute RefreshOutcome.Invalid
            val time = now()
            if (!user.enabled || session.revoked || session.expiresAt <= time) return@execute RefreshOutcome.Invalid
            val previous = sessions.findRefresh(digest) ?: return@execute RefreshOutcome.Invalid
            if (previous.consumedAt != null) {
                val successor = previous.successorDigest?.let(sessions::findRefresh)
                if (previous.requestId == requestId && previous.retryUntil != null && time < previous.retryUntil &&
                    previous.retryCiphertext != null && successor != null && successor.consumedAt == null) {
                    return@execute RefreshOutcome.Success(tokens.decrypt(previous.retryCiphertext, digest))
                }
                sessions.revokeSession(session.id)
                return@execute RefreshOutcome.Reused
            }
            val next = tokens.newRefreshToken()
            val pair = tokens.issue(user.id, session.id, next, time, session.expiresAt)
            sessions.rotate(previous, tokens.digest(next), requestId, time, minOf(time + graceMillis, session.expiresAt),
                if (graceMillis > 0) tokens.encrypt(pair, digest) else null)
            RefreshOutcome.Success(pair)
        }
        return when (outcome) {
            is RefreshOutcome.Success -> outcome.pair
            RefreshOutcome.Invalid -> throw BusinessException(FailureKind.UNAUTHENTICATED, "INVALID_REFRESH", "登录会话已失效，请重新登录")
            RefreshOutcome.Reused -> throw BusinessException(FailureKind.UNAUTHENTICATED, "REFRESH_REUSED", "登录会话已撤销，请重新登录")
        }
    }

    suspend fun authenticate(userId: String, sessionId: String): User? = transactions.execute {
        val user = users.findById(userId) ?: return@execute null
        val session = sessions.findSession(sessionId) ?: return@execute null
        user.takeIf { it.enabled && session.userId == user.id && !session.revoked && session.expiresAt > now() }
    }

    suspend fun logout(userId: String, sessionId: String, all: Boolean = false) {
        transactions.execute {
            users.findById(userId, lock = true) ?: return@execute
            val session = sessions.findSession(sessionId, lock = true) ?: return@execute
            if (session.userId != userId || session.revoked || session.expiresAt <= now()) return@execute
            if (all) sessions.revokeAll(userId) else sessions.revokeSession(sessionId)
        }
    }

    suspend fun prune() {
        val candidates = transactions.execute { sessions.cleanupCandidates(now()) }
        for (candidate in candidates) {
            transactions.execute {
                users.findById(candidate.userId, lock = true) ?: return@execute
                sessions.findSession(candidate.id, lock = true) ?: return@execute
                sessions.pruneSession(candidate.id, now())
            }
        }
        transactions.execute { sessions.pruneLoginAttempts(now()) }
    }

    private fun invalidCredentials(): Nothing = throw BusinessException(FailureKind.UNAUTHENTICATED, "INVALID_CREDENTIALS", "账号或密码不正确")
}

private sealed interface RefreshOutcome {
    data class Success(val pair: TokenPair) : RefreshOutcome
    data object Invalid : RefreshOutcome
    data object Reused : RefreshOutcome
}
