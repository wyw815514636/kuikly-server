package com.kuikly.server

import com.kuikly.server.api.ValidatableRequest
import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.plugins.configureHttp
import com.kuikly.server.plugins.httpFailure
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpPluginsTest {
    @Test
    fun `business failures retain their HTTP status and stable error code`() = testApplication {
        application {
            configureHttp()
            routing {
                get("/failure/{kind}") {
                    val kind = FailureKind.valueOf(requireNotNull(call.parameters["kind"]))
                    throw BusinessException(kind, "EXAMPLE_CONFLICT", "Public explanation")
                }
            }
        }
        for ((kind, status) in mapOf(
            FailureKind.BAD_REQUEST to 400,
            FailureKind.UNAUTHENTICATED to 401,
            FailureKind.FORBIDDEN to 403,
            FailureKind.NOT_FOUND to 404,
            FailureKind.CONFLICT to 409,
            FailureKind.UNAVAILABLE to 503,
        )) {
            val response = client.get("/failure/$kind")
            assertEquals(status, response.status.value)
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("EXAMPLE_CONFLICT", error.getValue("code").jsonPrimitive.content)
            assertEquals(response.headers["X-Request-ID"], error.getValue("traceId").jsonPrimitive.content)
        }
    }

    @Test
    fun `internal errors and missing routes use JSON without implementation details`() = testApplication {
        application {
            configureHttp()
            routing {
                get("/broken") { error("SELECT secret_password FROM users; internal-stack-marker") }
            }
        }
        val response = client.get("/broken")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertFalse(response.bodyAsText().contains("SELECT"))
        assertFalse(response.bodyAsText().contains("internal-stack-marker"))
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("INTERNAL_ERROR", error.getValue("code").jsonPrimitive.content)
        assertEquals(response.headers["X-Request-ID"], error.getValue("traceId").jsonPrimitive.content)

        val missing = client.get("/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("NOT_FOUND", Json.parseToJsonElement(missing.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `strict JSON and domain-independent request validation reject invalid input`() = testApplication {
        application {
            configureHttp()
            routing {
                post("/probe") {
                    call.respondText(call.receive<ProbeRequest>().name)
                }
            }
        }
        for (body in listOf("{", "{}", """{"name":"ok","unexpected":true}""", """{"name":""}""")) {
            val response = client.post("/probe") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
        }
        assertEquals("ok", client.post("/probe") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ok"}""")
        }.bodyAsText())
    }

    @Test
    fun `cancellation is propagated unchanged by the exception mapper`() {
        val cancellation = CancellationException("cancelled request")
        assertSame(cancellation, assertFailsWith<CancellationException> { httpFailure(cancellation) })
    }

    @Test
    fun `concurrent requests keep isolated MDC through dispatcher changes`() = testApplication {
        application {
            configureHttp()
            routing {
                get("/trace") {
                    val original = MDC.get("traceId")
                    val resumed = withContext(Dispatchers.Default) {
                        delay(5)
                        MDC.get("traceId")
                    }
                    assertNotNull(original)
                    assertEquals(original, resumed)
                    call.respondText(resumed)
                }
            }
        }
        startApplication()
        val ids = coroutineScope {
            (1..12).map {
                async {
                    val response = client.get("/trace") { headers.append("X-Request-ID", "untrusted-client-id") }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val requestId = assertNotNull(response.headers["X-Request-ID"])
                    assertEquals(requestId, response.bodyAsText())
                    assertTrue(requestId != "untrusted-client-id")
                    requestId
                }
            }.awaitAll()
        }
        assertEquals(12, ids.toSet().size)
    }
}

@Serializable
private data class ProbeRequest(val name: String) : ValidatableRequest {
    override fun validationErrors(): List<String> = if (name.isBlank()) listOf("name must not be blank") else emptyList()
}
