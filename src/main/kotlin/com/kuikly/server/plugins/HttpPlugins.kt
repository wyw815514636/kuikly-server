package com.kuikly.server.plugins

import com.kuikly.server.api.ValidatableRequest
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.json.Json

fun Application.configureHttp() {
    configureRequestLogging()
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = true
        })
    }
    install(RequestValidation) {
        validate<ValidatableRequest> { request ->
            val errors = request.validationErrors()
            if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
        }
    }
    configureErrors()
}
