package com.kuikly.server.config

import io.ktor.server.config.ApplicationConfig

data class ApiDocsSettings(val enabled: Boolean) {
    companion object {
        fun load(config: ApplicationConfig): ApiDocsSettings {
            val explicit = config.propertyOrNull("docs.enabled")?.getString()?.takeIf { it.isNotBlank() }
            val enabled = if (explicit == null) {
                (config.propertyOrNull("app.environment")?.getString() ?: "dev") in setOf("dev", "test")
            } else {
                requireNotNull(explicit.toBooleanStrictOrNull()) { "docs.enabled must be true or false" }
            }
            return ApiDocsSettings(enabled)
        }
    }
}
