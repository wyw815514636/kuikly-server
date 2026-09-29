package com.kuikly.server.plugins

import com.kuikly.server.api.ApiError
import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext

internal data class HttpFailure(val status: HttpStatusCode, val code: String, val message: String)

internal fun httpFailure(cause: Exception): HttpFailure = when (cause) {
    is CancellationException -> throw cause
    is BusinessException -> HttpFailure(
        when (cause.kind) {
            FailureKind.BAD_REQUEST -> HttpStatusCode.BadRequest
            FailureKind.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
            FailureKind.FORBIDDEN -> HttpStatusCode.Forbidden
            FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
            FailureKind.CONFLICT -> HttpStatusCode.Conflict
            FailureKind.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            FailureKind.TOO_MANY_REQUESTS -> HttpStatusCode.TooManyRequests
        },
        cause.code,
        cause.publicMessage,
    )
    is PayloadTooLargeException -> HttpFailure(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", "请求体过大")
    is UnsupportedMediaTypeException -> HttpFailure(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "不支持的请求格式")
    is RequestValidationException, is BadRequestException, is ContentTransformationException ->
        HttpFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "请求参数不合法")
    else -> HttpFailure(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "服务内部异常，请稍后重试")
}

internal fun Application.configureErrors() {
    val logger = environment.log
    install(StatusPages) {
        exception<Exception> { call, cause ->
            val failure = httpFailure(cause)
            val traceId = requireNotNull(call.callId)
            if (failure.status == HttpStatusCode.InternalServerError) {
                withContext(MDCContext(mapOf("traceId" to traceId))) {
                    logger.error("Unhandled request failure", cause)
                }
            }
            call.respond(failure.status, ApiError(failure.code, failure.message, traceId))
        }
        status(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
            HttpStatusCode.UnsupportedMediaType,
            HttpStatusCode.TooManyRequests,
        ) { call, status ->
            val code = when (status) {
                HttpStatusCode.Unauthorized -> "UNAUTHENTICATED"
                HttpStatusCode.Forbidden -> "FORBIDDEN"
                HttpStatusCode.NotFound -> "NOT_FOUND"
                HttpStatusCode.MethodNotAllowed -> "METHOD_NOT_ALLOWED"
                HttpStatusCode.UnsupportedMediaType -> "UNSUPPORTED_MEDIA_TYPE"
                else -> "TOO_MANY_REQUESTS"
            }
            call.respond(status, ApiError(code, status.description, requireNotNull(call.callId)))
        }
    }
}
