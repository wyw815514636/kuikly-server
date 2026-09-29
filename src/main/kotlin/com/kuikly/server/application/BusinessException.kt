package com.kuikly.server.application

enum class FailureKind {
    BAD_REQUEST,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    UNAVAILABLE,
    TOO_MANY_REQUESTS,
}

class BusinessException(
    val kind: FailureKind,
    val code: String,
    val publicMessage: String,
) : RuntimeException(publicMessage)
