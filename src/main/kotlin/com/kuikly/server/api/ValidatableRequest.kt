package com.kuikly.server.api

interface ValidatableRequest {
    fun validationErrors(): List<String>
}
