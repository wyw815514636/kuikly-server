package com.kuikly.server
import com.kuikly.server.config.DatabaseSettings
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.*
@Tag("integration") @Testcontainers
class AdminDatabaseIntegrationTest {
 companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:17.11-alpine") }
 @Test fun `management migration establishes independent identities and protected role`() {
  val settings=DatabaseSettings(postgres.jdbcUrl,postgres.username,postgres.password)
  DatabaseRuntime.migrate(settings)
  DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { c ->
   c.createStatement().use { s ->
    s.executeQuery("SELECT to_regclass('app.admin_accounts') IS NOT NULL").use { it.next();assertTrue(it.getBoolean(1),"Independent admin accounts must exist") }
    s.executeQuery("SELECT count(*) FROM app.admin_roles WHERE system = TRUE").use { it.next();assertEquals(1,it.getInt(1)) }
    s.executeQuery("SELECT count(*) FROM app.admin_accounts").use { it.next();assertEquals(0,it.getInt(1),"No default credentials") }
   }
  }
  assertEquals(0,DatabaseRuntime.migrate(settings))
 }
}
