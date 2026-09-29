package com.kuikly.server.infrastructure.database

import com.kuikly.server.application.ReadinessProbe
import com.kuikly.server.application.TransactionRunner
import com.kuikly.server.config.DatabaseSettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

class DatabaseRuntime private constructor(
    private val dataSource: HikariDataSource,
    private val database: Database,
    settings: DatabaseSettings,
) : ReadinessProbe, AutoCloseable {
    private val draining = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val dispatcher = Dispatchers.IO.limitedParallelism(settings.maximumPoolSize)
    val transactions: TransactionRunner = JdbcTransactionRunner(database, dispatcher, settings.queryTimeoutSeconds)
    val isClosed: Boolean get() = dataSource.isClosed

    override suspend fun isReady(): Boolean {
        if (draining.get() || isClosed) return false
        return withContext(dispatcher) {
            if (draining.get() || isClosed) return@withContext false
            try {
                dataSource.connection.use { it.isValid(1) } && !draining.get()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SQLException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    fun beginShutdown() {
        draining.set(true)
    }

    override fun close() {
        beginShutdown()
        if (closed.compareAndSet(false, true)) {
            try {
                TransactionManager.closeAndUnregister(database)
            } finally {
                dataSource.close()
            }
        }
    }

    companion object {
        fun migrate(settings: DatabaseSettings): Int = createDataSource(settings, settings.migrationTimeoutSeconds).use { dataSource ->
            flyway(dataSource).migrate().migrationsExecuted
        }

        fun open(settings: DatabaseSettings): DatabaseRuntime {
            val dataSource = createDataSource(settings)
            try {
                val migrations = flyway(dataSource)
                try {
                    migrations.validate()
                } catch (failure: FlywayException) {
                    throw IllegalStateException("Database schema validation failed; run the migration command", failure)
                }
                check(migrations.info().pending().isEmpty()) { "Database migrations are pending; run the migration command" }
                return DatabaseRuntime(dataSource, Database.connect(dataSource), settings)
            } catch (failure: Throwable) {
                dataSource.close()
                throw failure
            }
        }

        private fun flyway(dataSource: HikariDataSource): Flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .validateMigrationNaming(true)
            .load()

        private fun createDataSource(
            settings: DatabaseSettings,
            statementTimeoutSeconds: Int = settings.queryTimeoutSeconds,
        ): HikariDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.maximumPoolSize
                minimumIdle = minOf(2, settings.maximumPoolSize)
                connectionTimeout = settings.connectionTimeoutMillis
                validationTimeout = 500
                initializationFailTimeout = settings.connectionTimeoutMillis
                connectionInitSql = "SET statement_timeout = ${statementTimeoutSeconds * 1_000}"
                addDataSourceProperty("connectTimeout", (settings.connectionTimeoutMillis + 999) / 1_000)
                addDataSourceProperty("socketTimeout", statementTimeoutSeconds + 5)
                addDataSourceProperty("ApplicationName", "kuikly-server")
            },
        )
    }
}
