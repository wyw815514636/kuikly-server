package com.kuikly.server.api

import com.kuikly.server.config.ApiDocsSettings
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureApiDocumentation(settings: ApiDocsSettings) {
    if (!settings.enabled) return
    val contract = requireNotNull(Application::class.java.getResource("/openapi/kuikly.json")) {
        "Packaged OpenAPI contract is missing"
    }.readText()
    routing {
        get("/openapi.json") { call.respondText(contract, ContentType.Application.Json) }
        swaggerUI(path = "docs", apiUrl = "kuikly.json", api = contract) {
            version = "5.31.0"
        }
    }
}
