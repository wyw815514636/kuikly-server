package com.kuikly.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.flywaydb.core.extensibility.Plugin
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.slf4j.MDC
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Timeout(30)
class DependencyCompatibilityTest {
    @Test
    fun `Netty serves a Koin dependency through the generated JSON serializer`() = runBlocking {
        val server = embeddedServer(
            Netty,
            host = "127.0.0.1",
            port = 0,
        ) {
            install(Koin) {
                modules(module { single { ProbeResponse("ok", LocalDate(2026, 9, 28)) } })
            }
            install(ContentNegotiation) {
                json()
            }
            val response by inject<ProbeResponse>()
            routing {
                get("/compatibility") {
                    call.respond(response)
                }
            }
        }

        try {
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/compatibility"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                assertEquals(200, response.statusCode())
                assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
                val payload = Json.decodeFromString<ProbeResponse>(response.body())
                assertEquals("ok", payload.status)
                assertEquals(LocalDate(2026, 9, 28), payload.date)
            }
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 5_000)
        }
    }

    @Test
    fun `coroutine MDC propagates to a worker and restores the caller context`() = runBlocking {
        val originalContext = MDC.getCopyOfContextMap()
        try {
            MDC.put("traceId", "caller")
            withContext(Dispatchers.Default + MDCContext(mapOf("traceId" to "dependency-probe"))) {
                delay(1)
                assertEquals("dependency-probe", MDC.get("traceId"))
            }
            assertEquals("caller", MDC.get("traceId"))
        } finally {
            if (originalContext == null) MDC.clear() else MDC.setContextMap(originalContext)
        }
    }

    @Test
    fun `PostgreSQL driver and Flyway provider load without a database connection`() {
        val jdbcUrl = "jdbc:postgresql://127.0.0.1:1/dependency_probe"
        val driver = DriverManager.getDriver(jdbcUrl)
        assertTrue(driver.acceptsURL(jdbcUrl))

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = driver.javaClass.name
            maximumPoolSize = 2
            minimumIdle = 0
        }
        config.validate()

        HikariDataSource().use { dataSource ->
            config.copyStateTo(dataSource)
            val flyway = Flyway.configure().dataSource(dataSource).load()
            assertNotNull(flyway.configuration.dataSource)
            assertTrue(ServiceLoader.load(Plugin::class.java).any {
                it.javaClass.name == "org.flywaydb.database.postgresql.PostgreSQLDatabaseType"
            })
        }
    }

    @Test
    fun `Exposed v1 and datetime extension compile in the selected dependency graph`() {
        assertEquals("dependency_probe", ProbeTable.tableName)
        assertEquals(listOf("id", "created_on"), ProbeTable.columns.map { it.name })
    }
}

@Serializable
private data class ProbeResponse(val status: String, val date: LocalDate)

private object ProbeTable : Table("dependency_probe") {
    val id = integer("id")
    val createdOn = date("created_on")
}
