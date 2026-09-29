package com.kuikly.server
import com.kuikly.server.config.DatabaseSettings
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.Base64
import kotlin.test.*
@Tag("integration") @Testcontainers
class AdminHttpIntegrationTest {
 companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:17.11-alpine") }
 @Test fun `HTTP requires origin csrf and a management session`() {
  DatabaseRuntime.migrate(DatabaseSettings(postgres.jdbcUrl,postgres.username,postgres.password))
  testApplication {
   environment {config=validConfig().apply {
    put("database.jdbcUrl",postgres.jdbcUrl);put("database.username",postgres.username);put("database.password",postgres.password)
    put("admin.enabled","true");put("admin.origins","http://localhost");put("admin.secretKey",Base64.getEncoder().encodeToString(ByteArray(32){4}))
   }}
   application {module()}
   val csrf=client.get("/api/admin/v1/auth/csrf")
   assertEquals(HttpStatusCode.OK,csrf.status)
   assertEquals("no-store",csrf.headers["Cache-Control"])
   val token=Json.parseToJsonElement(csrf.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
   val cookie=csrf.headers.getAll(HttpHeaders.SetCookie)!!.first().substringBefore(';')
   assertEquals(HttpStatusCode.Forbidden,client.post("/api/admin/v1/auth/login"){contentType(ContentType.Application.Json);setBody("""{"username":"unknown","password":"wrong"}""")}.status)
   assertEquals(HttpStatusCode.Unauthorized,client.post("/api/admin/v1/auth/login"){
    header("Origin","http://localhost");header("X-CSRF-Token",token);header("Cookie",cookie)
    contentType(ContentType.Application.Json);setBody("""{"username":"unknown","password":"wrong"}""")
   }.status)
   assertEquals(HttpStatusCode.Unauthorized,client.get("/api/admin/v1/me"){bearerAuth("client-token")}.status)
  }
 }
}
