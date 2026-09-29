package com.kuikly.server

import com.kuikly.server.api.configureHealthRoutes
import com.kuikly.server.api.configureApiDocumentation
import com.kuikly.server.config.ApiDocsSettings
import com.kuikly.server.application.ReadinessProbe
import com.kuikly.server.application.TransactionRunner
import com.kuikly.server.config.ServerConfig
import com.kuikly.server.config.AuthSettings
import com.kuikly.server.infrastructure.createUserCommand
import com.kuikly.server.modules.auth.api.configureAuthentication
import com.kuikly.server.modules.auth.application.AuthService
import com.kuikly.server.modules.auth.infrastructure.ExposedAuthRepository
import com.kuikly.server.modules.auth.infrastructure.PasswordCodec
import com.kuikly.server.modules.auth.infrastructure.TokenCodec
import com.kuikly.server.modules.user.api.configureUserRoutes
import com.kuikly.server.modules.user.application.UserService
import com.kuikly.server.modules.user.infrastructure.ExposedUserRepository
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import com.kuikly.server.plugins.configureHttp
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.netty.EngineMain
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.firstOrNull() == "migrate") {
        require(args.size == 1) { "The migration command accepts no additional arguments" }
        DatabaseRuntime.migrate(ServerConfig.load(ApplicationConfig("application.yaml")).database)
    } else if (args.firstOrNull() == "create-user") {
        createUserCommand(args)
    } else {
        EngineMain.main(args)
    }
}

fun Application.module() {
    val settings = ServerConfig.load(environment.config)
    val authSettings = AuthSettings.load(environment.config)
    val docsSettings = ApiDocsSettings.load(environment.config)
    val database = DatabaseRuntime.open(settings.database)
    try {
        val users = ExposedUserRepository()
        val tokens = TokenCodec(authSettings)
        val auth = AuthService(database.transactions, users, ExposedAuthRepository(), PasswordCodec(), tokens,
            authSettings.sessionSeconds * 1000, authSettings.graceSeconds * 1000, System::currentTimeMillis)
        val userService = UserService(database.transactions, users)
        install(Koin) {
            modules(module {
                single { database }
                single<TransactionRunner> { database.transactions }
                single<ReadinessProbe> { database }
                single { auth }
                single { userService }
            })
        }
        configureHttp()
        configureHealthRoutes(database)
        configureAuthentication(auth, tokens.verifier)
        configureUserRoutes(userService)
        configureApiDocumentation(docsSettings)
        val cleanup = launch {
            while (isActive) {
                delay(60_000)
                try { auth.prune() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { environment.log.error("Authentication record cleanup failed", failure) }
            }
        }
        monitor.subscribe(ApplicationStopPreparing) { database.beginShutdown(); cleanup.cancel() }
        monitor.subscribe(ApplicationStopped) {
            runBlocking { cleanup.cancelAndJoin() }
            database.close()
        }
    } catch (failure: Throwable) {
        database.close()
        throw failure
    }
}
