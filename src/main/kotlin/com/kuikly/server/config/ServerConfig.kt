package com.kuikly.server.config

import io.ktor.server.config.ApplicationConfig

data class ServerConfig(val environment: String, val database: DatabaseSettings) {
    companion object {
        fun load(config: ApplicationConfig): ServerConfig {
            val environment = config.propertyOrNull("app.environment")?.getString() ?: "dev"
            require(environment in setOf("dev", "test", "staging", "prod")) { "Invalid app.environment" }
            return ServerConfig(
                environment,
                DatabaseSettings(
                    jdbcUrl = config.required("database.jdbcUrl"),
                    username = config.required("database.username"),
                    password = config.required("database.password"),
                    maximumPoolSize = config.integer("database.maximumPoolSize", 8),
                    connectionTimeoutMillis = config.integer("database.connectionTimeoutMillis", 3_000).toLong(),
                    queryTimeoutSeconds = config.integer("database.queryTimeoutSeconds", 5),
                    migrationTimeoutSeconds = config.integer("database.migrationTimeoutSeconds", 300),
                ),
            )
        }
    }
}

data class DatabaseSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 8,
    val connectionTimeoutMillis: Long = 3_000,
    val queryTimeoutSeconds: Int = 5,
    val migrationTimeoutSeconds: Int = 300,
) {
    init {
        require(jdbcUrl.startsWith("jdbc:postgresql://")) { "database.jdbcUrl must use PostgreSQL" }
        require(username.isNotBlank()) { "database.username is required" }
        require(password.isNotBlank()) { "database.password is required" }
        require(maximumPoolSize in 1..64) { "database.maximumPoolSize must be between 1 and 64" }
        require(connectionTimeoutMillis in 1_000..60_000) { "database.connectionTimeoutMillis must be between 1000 and 60000" }
        require(queryTimeoutSeconds in 1..300) { "database.queryTimeoutSeconds must be between 1 and 300" }
        require(migrationTimeoutSeconds in 1..3_600) { "database.migrationTimeoutSeconds must be between 1 and 3600" }
    }

    override fun toString(): String =
        "DatabaseSettings(connection=<redacted>, maximumPoolSize=$maximumPoolSize, queryTimeoutSeconds=$queryTimeoutSeconds)"
}

private fun ApplicationConfig.required(key: String): String =
    propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$key is required")

private fun ApplicationConfig.integer(key: String, default: Int): Int {
    val value = propertyOrNull(key)?.getString() ?: return default
    return value.toIntOrNull() ?: throw IllegalArgumentException("$key must be an integer")
}
