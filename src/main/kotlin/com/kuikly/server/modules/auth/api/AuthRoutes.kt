package com.kuikly.server.modules.auth.api

import com.kuikly.server.api.ValidatableRequest
import com.kuikly.server.application.BusinessException
import com.auth0.jwt.interfaces.JWTVerifier
import com.kuikly.server.modules.auth.application.AuthService
import com.kuikly.server.modules.auth.domain.TokenPair
import com.kuikly.server.modules.user.domain.User
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

data class AuthPrincipal(val user: User, val sessionId: String)

fun Application.configureAuthentication(auth: AuthService, jwtVerifier: JWTVerifier) {
    val logger = environment.log
    install(Authentication) {
        jwt("access") {
            realm = "kuikly"
            verifier(jwtVerifier)
            validate { credential ->
                val userId = credential.payload.subject
                val sessionId = credential.payload.getClaim("sid").asString()
                if (userId == null || sessionId == null || !isCanonicalId(userId) || !isCanonicalId(sessionId)) null
                else auth.authenticate(userId, sessionId)?.let { AuthPrincipal(it, sessionId) }
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized) }
        }
    }
    routing {
        route("/api/v1/auth") {
            install(RequestBodyLimit) { bodyLimit { 4_096 } }
            post("/login") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val request = call.receive<LoginRequest>()
                call.respond(auth.login(request.username, request.password).response())
            }
            post("/refresh") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val request = call.receive<RefreshRequest>()
                try { call.respond(auth.refresh(request.refreshToken, request.requestId).response()) }
                catch (failure: BusinessException) {
                    if (failure.code == "REFRESH_REUSED") logger.warn("Refresh token replay detected; session family revoked")
                    throw failure
                }
            }
            authenticate("access") {
                post("/logout") {
                    val actor = requireNotNull(call.principal<AuthPrincipal>())
                    auth.logout(actor.user.id, actor.sessionId)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/logout-all") {
                    val actor = requireNotNull(call.principal<AuthPrincipal>())
                    auth.logout(actor.user.id, actor.sessionId, all = true)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

@Serializable
private data class LoginRequest(val username: String, val password: String) : ValidatableRequest {
    override fun validationErrors(): List<String> = buildList {
        if (!username.matches(Regex("[a-z0-9][a-z0-9_.-]{2,63}"))) add("Invalid username")
        if (password.length !in 1..128) add("Invalid password length")
    }
    override fun toString(): String = "LoginRequest(credentials=<redacted>)"
}

@Serializable
private data class RefreshRequest(val refreshToken: String, val requestId: String) : ValidatableRequest {
    override fun validationErrors(): List<String> = buildList {
        if (!refreshToken.matches(Regex("[A-Za-z0-9_-]{43}"))) add("Invalid refresh token")
        if (!isCanonicalId(requestId)) add("Invalid request ID")
    }
    override fun toString(): String = "RefreshRequest(credentials=<redacted>)"
}

@Serializable
private data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresIn: Long,
    val sessionId: String, val tokenType: String = "Bearer") {
    override fun toString(): String = "TokenResponse(credentials=<redacted>)"
}

private fun TokenPair.response() = TokenResponse(accessToken, refreshToken, expiresIn, sessionId)

internal fun isCanonicalId(value: String): Boolean =
    value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
