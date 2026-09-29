package com.kuikly.server.modules.user.api

import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.modules.auth.api.AuthPrincipal
import com.kuikly.server.modules.auth.api.isCanonicalId
import com.kuikly.server.modules.user.application.UserService
import com.kuikly.server.modules.user.domain.User
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun Application.configureUserRoutes(users: UserService) {
    routing {
        authenticate("access") {
            get("/api/v1/users/me") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(requireNotNull(call.principal<AuthPrincipal>()).user.response())
            }
            get("/api/v1/users/{id}") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val id = requireNotNull(call.parameters["id"])
                if (!isCanonicalId(id)) throw BusinessException(FailureKind.BAD_REQUEST, "INVALID_REQUEST", "用户 ID 不合法")
                call.respond(users.findVisible(requireNotNull(call.principal<AuthPrincipal>()).user, id).response())
            }
            get("/api/v1/admin/me") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val actor = requireNotNull(call.principal<AuthPrincipal>()).user
                users.requireAdmin(actor)
                call.respond(actor.response())
            }
        }
    }
}

@Serializable
private data class UserResponse(val id: String, val username: String, val role: String)

private fun User.response() = UserResponse(id, username, role.name)
