package com.kuikly.server.api

import com.kuikly.server.application.ReadinessProbe
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun Application.configureHealthRoutes(readiness: ReadinessProbe) {
    routing {
        get("/health") {
            call.respond(HealthResponse("UP"))
        }
        get("/ready") {
            if (readiness.isReady()) {
                call.respond(HealthResponse("UP"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("DOWN"))
            }
        }
    }
}

@Serializable
private data class HealthResponse(val status: String)
