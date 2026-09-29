package com.kuikly.server

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodedPath
import io.swagger.v3.parser.OpenAPIV3Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenApiContractTest {
    @Test
    fun `contract parses without diagnostics and operations have unique identities`() {
        val result = OpenAPIV3Parser().readContents(ApiContract.source)
        assertEquals(emptyList(), result.messages.orEmpty())
        val spec = assertNotNull(result.openAPI)
        assertEquals("3.0.3", spec.openapi)
        val operations = spec.paths.values.flatMap { it.readOperations() }
        assertEquals(9, operations.size)
        assertEquals(operations.size, operations.map { it.operationId }.toSet().size)
        assertTrue(operations.all { !it.operationId.isNullOrBlank() })
        assertFalse(ApiContract.validator.validateRequest(SimpleRequest.Builder.post("/api/v1/auth/login")
            .withContentType("application/json").withBody("""{"username":"alice","password":"valid-password"}""").build()).hasErrors())
    }

    @Test
    fun `contract rejects unknown request fields and invalid refresh request ids`() {
        for ((path, body) in listOf(
            "/api/v1/auth/login" to """{"username":"alice","password":"valid-password","role":"ADMIN"}""",
            "/api/v1/auth/refresh" to """{"refreshToken":"${"a".repeat(43)}","requestId":"not-a-uuid"}""",
        )) {
            val report = ApiContract.validator.validateRequest(SimpleRequest.Builder.post(path)
                .withContentType("application/json").withBody(body).build())
            assertTrue(report.hasErrors(), "Invalid request escaped contract validation: $path")
        }
    }

    @Test
    fun `valid response bodies still require cache prevention and request identity headers`() {
        val body = """{"id":"00000000-0000-0000-0000-000000000001","username":"alice","role":"USER"}"""
        for (missing in listOf<String?>(null, "Cache-Control", "X-Request-ID")) {
            val builder = SimpleResponse.Builder.ok().withContentType("application/json").withBody(body)
            if (missing != "Cache-Control") builder.withHeader("Cache-Control", "no-store")
            if (missing != "X-Request-ID") builder.withHeader("X-Request-ID", "00000000-0000-0000-0000-000000000002")
            val report = ApiContract.validator.validateResponse("/api/v1/users/me", Request.Method.GET, builder.build())
            assertEquals(missing != null, report.hasErrors(), "Missing response header escaped validation: $missing")
        }
    }

    @Test
    fun `validator rejects response drift rather than accepting arbitrary JSON`() {
        val cases = listOf(
            Triple("/api/v1/users/me", 200, """{"id":"00000000-0000-0000-0000-000000000001","username":"alice"}"""),
            Triple("/api/v1/users/me", 200, """{"id":"00000000-0000-0000-0000-000000000001","username":123,"role":"USER"}"""),
            Triple("/api/v1/users/me", 401, """{"code":"UNAUTHENTICATED","message":"Login required"}"""),
            Triple("/api/v1/users/me", 201, "{}"),
        )
        for ((path, status, body) in cases) {
            val report = ApiContract.validator.validateResponse(path, Request.Method.GET,
                SimpleResponse.Builder.status(status).withContentType("application/json").withBody(body)
                    .withHeader("Cache-Control", "no-store")
                    .withHeader("X-Request-ID", "00000000-0000-0000-0000-000000000002").build())
            assertTrue(report.hasErrors(), "Response drift escaped contract validation: $path $status")
        }
    }
}

internal object ApiContract {
    val source = requireNotNull(javaClass.getResource("/openapi/kuikly.json")).readText()
    val validator: OpenApiInteractionValidator = OpenApiInteractionValidator.createForInlineApiSpecification(source).build()
    val operations = Json.parseToJsonElement(source).jsonObject.getValue("paths").jsonObject.flatMap { (path, item) ->
        item.jsonObject.keys.filter { it in setOf("get", "post", "put", "patch", "delete", "head", "options", "trace") }
            .map { "${it.uppercase()} $path" }
    }.toSet()
}

internal class ContractCoverage {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    suspend fun validate(response: HttpResponse) {
        val path = response.call.request.url.encodedPath
        val method = Request.Method.valueOf(response.call.request.method.value)
        val builder = SimpleResponse.Builder.status(response.status.value)
        response.headers.entries().forEach { (name, values) -> builder.withHeader(name, values) }
        val body = response.bodyAsText()
        if (body.isNotEmpty()) builder.withBody(body)
        val report = ApiContract.validator.validateResponse(path, method, builder.build())
        assertFalse(report.hasErrors(), "OpenAPI response mismatch for $method $path: ${report.messages.map { it.key }}")
        val exact = "${method.name} $path"
        val template = if (exact in ApiContract.operations) exact else ApiContract.operations.single { operation ->
            val (operationMethod, route) = operation.split(' ', limit = 2)
            val expected = route.split('/')
            val actual = path.split('/')
            operationMethod == method.name && expected.size == actual.size && expected.zip(actual).all { (a, b) ->
                (a.startsWith('{') && a.endsWith('}')) || a == b
            }
        }
        seen += template
    }

    fun assertComplete() = assertEquals(ApiContract.operations, seen.toSet(), "Every documented operation must have a real HTTP response test")
}
