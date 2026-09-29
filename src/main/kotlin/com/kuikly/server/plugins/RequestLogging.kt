package com.kuikly.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.util.UUID

internal fun Application.configureRequestLogging() {
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        replyToHeader("X-Request-ID")
    }
    install(CallLogging) {
        disableDefaultColors()
        mdc("traceId") { it.callId }
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} ${call.response.status()?.value ?: 0}"
        }
    }
}
