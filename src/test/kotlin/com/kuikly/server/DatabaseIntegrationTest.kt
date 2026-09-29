package com.kuikly.server

import com.kuikly.server.config.DatabaseSettings
import com.kuikly.server.config.ServerConfig
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.ktor.ext.getKoin
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URLClassLoader
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
@Timeout(60)
class DatabaseIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("infrastructure")
    }

    @Test
    fun `empty database requires migration and repeated migration is idempotent`() = runBlocking {
        val settings = newSettings()
        assertFailsWith<IllegalStateException> { DatabaseRuntime.open(settings) }
        assertEquals(2, DatabaseRuntime.migrate(settings))
        assertEquals(0, DatabaseRuntime.migrate(settings))
        val runtime = DatabaseRuntime.open(settings)
        runtime.use {
            assertTrue(it.isReady())
            it.beginShutdown()
            assertFalse(it.isReady())
        }
        assertTrue(runtime.isClosed)
        assertFalse(runtime.isReady())
    }

    @Test
    fun `Flyway upgrades an existing baseline without losing prior data`() {
        val settings = newSettings()
        DatabaseRuntime.migrate(settings)
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE app.existing_data (id INTEGER PRIMARY KEY)")
                statement.execute("INSERT INTO app.existing_data VALUES (7)")
            }
        }
        val upgraded = Flyway.configure()
            .dataSource(settings.jdbcUrl, settings.username, settings.password)
            .locations("classpath:db/migration", "classpath:db/test-upgrade")
            .load().migrate()
        assertEquals(1, upgraded.migrationsExecuted)
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM app.existing_data").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(7, rows.getInt(1))
                }
                statement.executeQuery("SELECT count(*) FROM app.upgrade_probe").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `migration has a separate deadline from short application queries`() {
        val settings = newSettings(queryTimeoutSeconds = 1)
        val directory = Files.createTempDirectory("kuikly-slow-migration")
        val migration = directory.resolve("db/migration/V900__slow_migration.sql")
        Files.createDirectories(migration.parent)
        val previousLoader = Thread.currentThread().contextClassLoader
        try {
            requireNotNull(previousLoader.getResourceAsStream("db/slow-migration/V900__slow_migration.sql")).use {
                Files.copy(it, migration)
            }
            URLClassLoader(arrayOf(directory.toUri().toURL()), previousLoader).use { loader ->
                Thread.currentThread().contextClassLoader = loader
                assertEquals(3, DatabaseRuntime.migrate(settings))
            }
        } finally {
            Thread.currentThread().contextClassLoader = previousLoader
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup and readiness work with a role lacking schema modification permission`() = runBlocking {
        val settings = newSettings()
        DatabaseRuntime.migrate(settings)
        val username = "runtime_${UUID.randomUUID().toString().replace("-", "")}"
        val password = UUID.randomUUID().toString()
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $username LOGIN PASSWORD '$password'")
                statement.execute("GRANT USAGE ON SCHEMA app, public TO $username")
                statement.execute("GRANT SELECT ON public.flyway_schema_history TO $username")
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA app TO $username")
            }
        }
        val restricted = settings.copy(username = username, password = password)
        DriverManager.getConnection(restricted.jdbcUrl, restricted.username, restricted.password).use { connection ->
            assertFailsWith<SQLException> {
                connection.createStatement().use { it.execute("CREATE TABLE app.forbidden_ddl (id INTEGER)") }
            }
        }
        DatabaseRuntime.open(restricted).use { assertTrue(it.isReady()) }
    }

    @Test
    fun `database outage fails readiness while liveness remains healthy`() {
        val settings = newSettings()
        DatabaseRuntime.migrate(settings)
        testApplication {
            environment {
                config = validConfig().apply {
                    put("database.jdbcUrl", settings.jdbcUrl)
                    put("database.username", settings.username)
                    put("database.password", settings.password)
                }
            }
            application { module() }
            assertEquals(HttpStatusCode.OK, client.get("/ready").status)
            val databaseName = settings.jdbcUrl.substringAfterLast('/')
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER DATABASE $databaseName ALLOW_CONNECTIONS false")
                    statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$databaseName'")
                }
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
    }

    @Test
    fun `startup rejects a changed migration checksum and closes its connections`() {
        val settings = newSettings()
        DatabaseRuntime.migrate(settings)
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("UPDATE flyway_schema_history SET checksum = 1 WHERE version = '1'")
            }
        }
        assertFailsWith<Exception> { DatabaseRuntime.open(settings) }
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND application_name = 'kuikly-server'",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `multiple table writes commit together and roll back together`() = runBlocking {
        val settings = newSettings()
        DatabaseRuntime.migrate(settings)
        DatabaseRuntime.open(settings).use { runtime ->
            runtime.transactions.execute { SchemaUtils.create(LeftRows, RightRows) }
            assertFailsWith<IllegalStateException> {
                runtime.transactions.execute {
                    LeftRows.insert { it[id] = 1 }
                    RightRows.insert { it[id] = 1 }
                    error("fail after both writes")
                }
            }
            runtime.transactions.execute {
                assertEquals(0L, LeftRows.selectAll().count())
                assertEquals(0L, RightRows.selectAll().count())
            }
            runtime.transactions.execute {
                LeftRows.insert { it[id] = 2 }
                RightRows.insert { it[id] = 2 }
            }
            runtime.transactions.execute {
                assertEquals(1L, LeftRows.selectAll().count())
                assertEquals(1L, RightRows.selectAll().count())
            }
        }
    }

    @Test
    fun `cancellation observed before commit rolls back a blocking transaction`() = runBlocking {
        val settings = newSettings()
        DatabaseRuntime.migrate(settings)
        DatabaseRuntime.open(settings).use { runtime ->
            runtime.transactions.execute { SchemaUtils.create(LeftRows) }
            val written = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val job = launch {
                runtime.transactions.execute {
                    LeftRows.insert { it[id] = 1 }
                    written.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            try {
                written.await()
                job.cancel()
            } finally {
                release.countDown()
                job.join()
            }
            assertTrue(job.isCancelled)
            runtime.transactions.execute { assertEquals(0L, LeftRows.selectAll().count()) }
        }
    }

    @Test
    fun `statement timeout aborts a slow query and the pool remains usable`() = runBlocking {
        val settings = newSettings(queryTimeoutSeconds = 1)
        DatabaseRuntime.migrate(settings)
        DatabaseRuntime.open(settings).use { runtime ->
            assertFailsWith<SQLException> {
                runtime.transactions.execute {
                    TransactionManager.current().exec("SELECT pg_sleep(3)") { rows -> rows.next() }
                }
            }
            assertTrue(runtime.isReady())
        }
    }

    @Test
    fun `application health readiness and shutdown follow the database lifecycle`() {
        val settings = newSettings()
        DatabaseRuntime.migrate(settings)
        var runtime: DatabaseRuntime? = null
        testApplication {
            environment {
                config = validConfig().apply {
                    put("database.jdbcUrl", settings.jdbcUrl)
                    put("database.username", settings.username)
                    put("database.password", settings.password)
                }
            }
            application {
                module()
                runtime = getKoin().get<DatabaseRuntime>()
            }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.OK, client.get("/ready").status)
            assertNotNull(runtime).beginShutdown()
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
        assertTrue(assertNotNull(runtime).isClosed)
    }

    private fun newSettings(queryTimeoutSeconds: Int = 3): DatabaseSettings {
        val databaseName = "probe_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $databaseName") }
        }
        return ServerConfig.load(validConfig().apply {
            put("database.jdbcUrl", "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$databaseName")
            put("database.username", postgres.username)
            put("database.password", postgres.password)
            put("database.queryTimeoutSeconds", queryTimeoutSeconds.toString())
        }).database
    }
}

private object LeftRows : Table("app.tx_left") {
    val id = integer("id")
    override val primaryKey = PrimaryKey(id)
}

private object RightRows : Table("app.tx_right") {
    val id = integer("id")
    override val primaryKey = PrimaryKey(id)
}
