package com.kuikly.server.application

fun interface ReadinessProbe {
    suspend fun isReady(): Boolean
}
