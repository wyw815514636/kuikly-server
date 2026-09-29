package com.kuikly.server

import com.kuikly.server.api.configureApiDocumentation
import com.kuikly.server.config.ApiDocsSettings
import com.kuikly.server.plugins.configureHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDocumentationTest {
    @Test
    fun `documentation defaults to development only and rejects ambiguous flags`() {
        for (environment in listOf("dev", "test", "staging", "prod")) {
            val config = MapApplicationConfig("app.environment" to environment)
            assertEquals(environment in setOf("dev", "test"), ApiDocsSettings.load(config).enabled)
            config.put("docs.enabled", "true")
            assertTrue(ApiDocsSettings.load(config).enabled)
            config.put("docs.enabled", "false")
            assertFalse(ApiDocsSettings.load(config).enabled)
            config.put("docs.enabled", "yes")
            assertFailsWith<IllegalArgumentException> { ApiDocsSettings.load(config) }
        }
    }

    @Test
    fun `enabled documentation serves the packaged contract and Swagger UI`() = testApplication {
        application { configureHttp(); configureApiDocumentation(ApiDocsSettings(true)) }
        val response = client.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val expected = requireNotNull(javaClass.getResource("/openapi/kuikly.json")).readText()
        assertEquals(expected, response.bodyAsText())
        val ui = client.get("/docs")
        assertEquals(HttpStatusCode.OK, ui.status)
        assertTrue(ui.bodyAsText().contains("SwaggerUIBundle"))
        assertTrue(ui.bodyAsText().contains("swagger-ui-dist@5.31.0"))
    }

    @Test
    fun `disabled documentation exposes neither UI nor contract`() = testApplication {
        application { configureHttp(); configureApiDocumentation(ApiDocsSettings(false)) }
        for (path in listOf("/docs", "/openapi.json", "/docs/kuikly.json")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
        }
    }
}
