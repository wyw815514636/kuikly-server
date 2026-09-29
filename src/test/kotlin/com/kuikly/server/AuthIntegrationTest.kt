package com.kuikly.server

import com.kuikly.server.application.BusinessException
import com.kuikly.server.config.AuthSettings
import com.kuikly.server.config.DatabaseSettings
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import com.kuikly.server.modules.auth.application.AuthService
import com.kuikly.server.modules.auth.infrastructure.ExposedAuthRepository
import com.kuikly.server.modules.auth.infrastructure.PasswordCodec
import com.kuikly.server.modules.auth.infrastructure.TokenCodec
import com.kuikly.server.modules.user.domain.UserRole
import com.kuikly.server.modules.user.infrastructure.ExposedUserRepository
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.ktor.ext.getKoin
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
@Timeout(60)
class AuthIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17.11-alpine").withDatabaseName("auth")
        private const val password = "long-test-password-12345"
    }

    @Test
    fun `login survives cleanup between conflict insert and bucket lock`() = fixture { f ->
        val key = f.tokens.digest("alice")
        f.runtime.transactions.execute { f.repository.takeLoginAttempt(key, f.now.get() - 60_001) }
        val inserted = CompletableDeferred<Unit>()
        val cleaned = CountDownLatch(1)
        val intercepted = AtomicBoolean(false)
        coroutineScope {
            val attempt = async {
                f.runtime.transactions.execute {
                    TransactionManager.current().registerInterceptor(object : StatementInterceptor {
                        override fun afterExecution(transaction: Transaction, contexts: List<StatementContext>, executedStatement: PreparedStatementApi) {
                            if (contexts.any { it.sql(transaction).startsWith("INSERT INTO app.login_attempts") } && intercepted.compareAndSet(false, true)) {
                                inserted.complete(Unit)
                                check(cleaned.await(10, TimeUnit.SECONDS))
                            }
                        }
                    })
                    f.repository.takeLoginAttempt(key, f.now.get())
                }
            }
            try {
                withTimeout(10_000) { inserted.await() }
                f.service.prune()
            } finally { cleaned.countDown() }
            assertTrue(attempt.await())
        }
    }

    @Test
    fun `maintenance respects user lock held by whole account logout`() = fixture { f ->
        val user = f.service.createUser("alice", password, UserRole.USER)
        val active = f.service.login("alice", password)
        val expired = f.service.login("alice", password)
        f.service.refresh(active.refreshToken, UUID.randomUUID().toString())
        f.now.addAndGet(10_001)
        val settings = f.settings
        withContext(Dispatchers.IO) {
            DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
                connection.prepareStatement("UPDATE app.auth_sessions SET expires_at = ? WHERE id = ?").use {
                    it.setLong(1, f.now.get() - 1); it.setString(2, expired.sessionId); it.executeUpdate()
                }
                connection.autoCommit = false
                connection.prepareStatement("SELECT id FROM app.users WHERE id = ? FOR UPDATE").use {
                    it.setString(1, user.id); it.executeQuery().use { rows -> assertTrue(rows.next()) }
                }
                coroutineScope {
                    val cleanup = async { f.service.prune() }
                    try {
                        withTimeout(5_000) {
                            while (!cleanup.isCompleted) {
                                val waiting = connection.createStatement().use { statement ->
                                    statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'").use {
                                        it.next(); it.getInt(1) > 0
                                    }
                                }
                                if (waiting) break
                                delay(10)
                            }
                        }
                        assertTrue(!cleanup.isCompleted, "Cleanup must wait for the user lock before touching session rows")
                        connection.prepareStatement("UPDATE app.auth_sessions SET revoked = TRUE WHERE user_id = ?").use {
                            it.setString(1, user.id); it.executeUpdate()
                        }
                        connection.commit()
                    } finally { connection.rollback() }
                    cleanup.await()
                }
            }
        }
        assertNull(f.service.authenticate(user.id, active.sessionId))
        f.runtime.transactions.execute { assertNull(f.repository.findSession(expired.sessionId)) }
    }

    @Test
    fun `HTTP enforces JWT claims ownership roles strict input and immediate revocation`() {
        val settings = settings()
        DatabaseRuntime.migrate(settings)
        testApplication {
            lateinit var service: AuthService
            environment {
                config = validConfig().apply {
                    put("database.jdbcUrl", settings.jdbcUrl)
                    put("database.username", settings.username)
                    put("database.password", settings.password)
                }
            }
            application { module(); service = getKoin().get() }
            startApplication()
            val coverage = ContractCoverage()
            val client = createClient {
                HttpResponseValidator { validateResponse { coverage.validate(it) } }
            }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.OK, client.get("/ready").status)
            val alice = service.createUser("alice", password, UserRole.USER)
            val bob = service.createUser("bob", password, UserRole.USER)
            service.createUser("admin", password, UserRole.ADMIN)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/me").status)
            val login = client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"$password"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            assertEquals("no-store", login.headers["Cache-Control"])
            val pair = Json.parseToJsonElement(login.bodyAsText()).jsonObject
            val access = pair.getValue("accessToken").jsonPrimitive.content
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/users/me") { bearerAuth(access) }.status)
            val self = client.get("/api/v1/users/${alice.id}") { bearerAuth(access) }
            assertEquals(HttpStatusCode.OK, self.status)
            assertTrue(!self.bodyAsText().contains("password", ignoreCase = true))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/users/${bob.id}") { bearerAuth(access) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/me") { bearerAuth(access) }.status)
            val admin = service.login("admin", password)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/me") { bearerAuth(admin.accessToken) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/users/${bob.id}") { bearerAuth(admin.accessToken) }.status)
            val key = AuthSettings.load(authConfig()).signingKey
            for (variant in listOf("expired", "audience", "issuer", "kind", "signature", "missing-exp", "sid")) {
                val builder = JWT.create().withSubject(alice.id)
                    .withIssuer(if (variant == "issuer") "wrong" else "kuikly-server")
                    .withAudience(if (variant == "audience") "wrong" else "kuikly-client")
                    .withClaim("kind", if (variant == "kind") "refresh" else "access")
                    .withClaim("sid", if (variant == "sid") admin.sessionId else pair.getValue("sessionId").jsonPrimitive.content)
                    .withIssuedAt(Instant.now().minusSeconds(120))
                if (variant != "missing-exp") builder.withExpiresAt(Instant.now().plusSeconds(if (variant == "expired") -60 else 60))
                val token = builder.sign(Algorithm.HMAC256(if (variant == "signature") ByteArray(32) else key))
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/me") { bearerAuth(token) }.status, variant)
            }
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"$password","role":"ADMIN"}""")
            }.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"${"a".repeat(20_000)}"}""")
            }.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/v1/auth/login") {
                setBody(object : OutgoingContent.ReadChannelContent() {
                    override val contentType = ContentType.Application.Json
                    override fun readFrom() = ByteReadChannel("""{"username":"alice","password":"${"a".repeat(20_000)}"}""")
                })
            }.status)
            val requestId = UUID.randomUUID().toString()
            val refreshBody = """{"refreshToken":"${pair.getValue("refreshToken").jsonPrimitive.content}","requestId":"$requestId"}"""
            val refreshed = client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(refreshBody) }
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val retried = client.post("/api/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(refreshBody) }
            assertEquals(refreshed.bodyAsText(), retried.bodyAsText())
            assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout") { bearerAuth(access) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/me") { bearerAuth(access) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/refresh") {
                contentType(ContentType.Application.Json); setBody(refreshBody)
            }.status)
            val bobTokens = service.login("bob", password)
            DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
                connection.createStatement().use { it.execute("UPDATE app.users SET enabled = FALSE WHERE username = 'bob'") }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/me") { bearerAuth(bobTokens.accessToken) }.status)
            assertFailsWith<BusinessException> { service.login("bob", password) }
            assertFailsWith<BusinessException> { service.refresh(bobTokens.refreshToken, UUID.randomUUID().toString()) }
            assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout-all") { bearerAuth(admin.accessToken) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/me") { bearerAuth(admin.accessToken) }.status)
            coverage.assertComplete()
        }
    }

    @Test
    fun `expired retry ciphertext is removed without losing replay detection`() = fixture { f ->
        f.service.createUser("alice", password, UserRole.USER)
        val first = f.service.login("alice", password)
        val request = UUID.randomUUID().toString()
        val next = f.service.refresh(first.refreshToken, request)
        val digest = f.tokens.digest(first.refreshToken)
        f.runtime.transactions.execute {
            val stored = assertNotNull(f.repository.findRefresh(digest))
            val ciphertext = assertNotNull(stored.retryCiphertext)
            assertTrue(!ciphertext.contains(next.refreshToken))
            assertTrue(!ciphertext.contains(next.accessToken))
        }
        f.now.addAndGet(10_001)
        f.service.prune()
        f.runtime.transactions.execute {
            val stored = assertNotNull(f.repository.findRefresh(digest))
            assertNull(stored.retryCiphertext)
            assertNotNull(stored.consumedAt)
        }
        assertEquals("REFRESH_REUSED", assertFailsWith<BusinessException> { f.service.refresh(first.refreshToken, request) }.code)
    }

    @Test
    fun `zero grace never recovers rotated credentials`() = fixture(graceSeconds = 0) { f ->
        f.service.createUser("alice", password, UserRole.USER)
        val first = f.service.login("alice", password)
        val request = UUID.randomUUID().toString()
        f.service.refresh(first.refreshToken, request)
        assertEquals("REFRESH_REUSED", assertFailsWith<BusinessException> { f.service.refresh(first.refreshToken, request) }.code)
    }

    @Test
    fun `migration upgrades V1 and preserves existing data`() {
        val settings = settings()
        Flyway.configure().dataSource(settings.jdbcUrl, settings.username, settings.password)
            .locations("classpath:db/migration").target("1").load().migrate()
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE app.existing (id INT PRIMARY KEY); INSERT INTO app.existing VALUES (7)") }
        }
        assertEquals(1, DatabaseRuntime.migrate(settings))
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM app.existing").use { assertTrue(it.next()); assertEquals(7, it.getInt(1)) }
                statement.executeQuery("SELECT count(*) FROM app.users").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
            }
        }
    }

    @Test
    fun `concurrent retry rotates once and late replay revokes only its family`() = fixture { f ->
        val user = f.service.createUser("alice", password, UserRole.USER)
        val original = f.service.login("alice", password)
        val otherDevice = f.service.login("alice", password)
        val request = UUID.randomUUID().toString()
        val results = coroutineScope {
            (1..8).map { async { f.service.refresh(original.refreshToken, request) } }.awaitAll()
        }
        assertEquals(1, results.distinct().size)
        val rotated = results.first()
        assertNotEquals(original.refreshToken, rotated.refreshToken)
        f.runtime.transactions.execute {
            assertNotNull(f.repository.findRefresh(f.tokens.digest(original.refreshToken))?.consumedAt)
            assertNull(f.repository.findRefresh(f.tokens.digest(rotated.refreshToken))?.consumedAt)
        }
        assertNotNull(f.service.authenticate(user.id, original.sessionId))
        f.now.addAndGet(10_001)
        assertEquals("REFRESH_REUSED", assertFailsWith<BusinessException> { f.service.refresh(original.refreshToken, request) }.code)
        assertNull(f.service.authenticate(user.id, original.sessionId))
        assertNotNull(f.service.authenticate(user.id, otherDevice.sessionId))
        assertFailsWith<BusinessException> { f.service.refresh(rotated.refreshToken, UUID.randomUUID().toString()) }
    }

    @Test
    fun `different retry id and already rotated successor cannot recover credentials`() = fixture { f ->
        val user = f.service.createUser("alice", password, UserRole.USER)
        val first = f.service.login("alice", password)
        val request = UUID.randomUUID().toString()
        f.service.refresh(first.refreshToken, request)
        assertEquals("REFRESH_REUSED", assertFailsWith<BusinessException> {
            f.service.refresh(first.refreshToken, UUID.randomUUID().toString())
        }.code)
        assertNull(f.service.authenticate(user.id, first.sessionId))
        val second = f.service.login("alice", password)
        val next = f.service.refresh(second.refreshToken, request)
        f.service.refresh(next.refreshToken, UUID.randomUUID().toString())
        assertFailsWith<BusinessException> { f.service.refresh(second.refreshToken, request) }
        assertNull(f.service.authenticate(user.id, second.sessionId))
    }

    @Test
    fun `logout all serializes with refresh and revokes every existing session`() = fixture { f ->
        val user = f.service.createUser("alice", password, UserRole.USER)
        val first = f.service.login("alice", password)
        val second = f.service.login("alice", password)
        coroutineScope {
            listOf(
                async { runCatching { f.service.refresh(first.refreshToken, UUID.randomUUID().toString()) } },
                async { f.service.logout(user.id, second.sessionId, all = true) },
            ).awaitAll()
        }
        assertNull(f.service.authenticate(user.id, first.sessionId))
        assertNull(f.service.authenticate(user.id, second.sessionId))
        assertFailsWith<BusinessException> { f.service.refresh(second.refreshToken, UUID.randomUUID().toString()) }
    }

    @Test
    fun `invalid refresh cannot revoke sessions and absolute expiry cannot be extended`() = fixture { f ->
        val user = f.service.createUser("alice", password, UserRole.USER)
        val first = f.service.login("alice", password)
        assertFailsWith<BusinessException> { f.service.refresh(f.tokens.newRefreshToken(), UUID.randomUUID().toString()) }
        assertNotNull(f.service.authenticate(user.id, first.sessionId))
        f.now.addAndGet(3_600_001)
        assertFailsWith<BusinessException> { f.service.refresh(first.refreshToken, UUID.randomUUID().toString()) }
        assertNull(f.service.authenticate(user.id, first.sessionId))
        f.service.prune()
        f.runtime.transactions.execute { assertNull(f.repository.findSession(first.sessionId)) }
    }

    @Test
    fun `login failures are generic and account attempt limit is durable`() = fixture { f ->
        f.service.createUser("alice", password, UserRole.USER)
        val known = assertFailsWith<BusinessException> { f.service.login("alice", "wrong") }
        val unknown = assertFailsWith<BusinessException> { f.service.login("unknown", "wrong") }
        assertEquals(known.code, unknown.code)
        repeat(4) { assertFailsWith<BusinessException> { f.service.login("alice", "wrong") } }
        assertEquals("LOGIN_THROTTLED", assertFailsWith<BusinessException> { f.service.login("alice", password) }.code)
        f.now.addAndGet(60_001)
        assertNotNull(f.service.login("alice", password))
    }

    private fun fixture(graceSeconds: Long = 10, block: suspend (Fixture) -> Unit) = runBlocking {
        val settings = settings()
        DatabaseRuntime.migrate(settings)
        DatabaseRuntime.open(settings).use { runtime ->
            val config = AuthSettings.load(authConfig().apply {
                put("auth.sessionSeconds", "3600")
                put("auth.graceSeconds", graceSeconds.toString())
            })
            val tokens = TokenCodec(config)
            val repository = ExposedAuthRepository()
            val now = AtomicLong(System.currentTimeMillis())
            val service = AuthService(runtime.transactions, ExposedUserRepository(), repository, PasswordCodec(), tokens,
                config.sessionSeconds * 1000, config.graceSeconds * 1000, now::get)
            block(Fixture(runtime, service, repository, tokens, now, settings))
        }
    }

    private fun settings(): DatabaseSettings {
        val name = "auth_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return DatabaseSettings("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$name", postgres.username, postgres.password)
    }
}

private data class Fixture(val runtime: DatabaseRuntime, val service: AuthService, val repository: ExposedAuthRepository,
    val tokens: TokenCodec, val now: AtomicLong, val settings: DatabaseSettings)
