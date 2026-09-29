package com.kuikly.server.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val code: String, val message: String, val traceId: String)
