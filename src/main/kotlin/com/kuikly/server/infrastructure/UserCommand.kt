package com.kuikly.server.infrastructure

import com.kuikly.server.config.ServerConfig
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import com.kuikly.server.modules.auth.infrastructure.PasswordCodec
import com.kuikly.server.modules.user.application.UserProvisioner
import com.kuikly.server.modules.user.domain.UserRole
import com.kuikly.server.modules.user.infrastructure.ExposedUserRepository
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import java.util.UUID

fun createUserCommand(args: Array<String>) {
    require(args.size in 2..3) { "Usage: create-user <username> [USER|ADMIN]" }
    val role = args.getOrNull(2)?.let(UserRole::valueOf) ?: UserRole.USER
    val console = requireNotNull(System.console()) { "Run the installed executable in an interactive terminal to enter a password securely" }
    val password = requireNotNull(console.readPassword("Password (15-128 characters): "))
    try {
        val confirmation = requireNotNull(console.readPassword("Confirm password: "))
        try { require(password.contentEquals(confirmation)) { "Passwords do not match" } }
        finally { confirmation.fill('\u0000') }
        val settings = ServerConfig.load(ApplicationConfig("application.yaml"))
        DatabaseRuntime.open(settings.database).use { database ->
            val provisioner = UserProvisioner(database.transactions, ExposedUserRepository(), PasswordCodec()) { UUID.randomUUID().toString() }
            val user = runBlocking { provisioner.create(args[1], String(password), role) }
            console.printf("Created user %s (%s)%n", user.id, user.role.name)
        }
    } finally { password.fill('\u0000') }
}
