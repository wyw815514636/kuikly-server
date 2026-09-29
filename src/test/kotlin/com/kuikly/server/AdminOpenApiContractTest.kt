package com.kuikly.server

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Test
import kotlin.test.*

class AdminOpenApiContractTest {
    @Test fun `management contract is present and rejects incomplete authenticated identity`() {
        val resource = javaClass.getResource("/openapi/admin.json")
        assertNotNull(resource, "Management contract must exist")
        val source = resource.readText()
        val parsed = OpenAPIV3Parser().readContents(source)
        assertEquals(emptyList(), parsed.messages.orEmpty())
        val validator = OpenApiInteractionValidator.createForInlineApiSpecification(source).build()
        val invalid = SimpleResponse.Builder.ok().withContentType("application/json")
            .withHeader("X-Request-ID", "test").withHeader("Cache-Control", "no-store")
            .withBody("""{"next":"authenticated"}""").build()
        assertTrue(validator.validateResponse("/api/admin/v1/auth/login", Request.Method.POST, invalid).hasErrors())
        val operations = parsed.openAPI.paths.values.flatMap { it.readOperations() }
        assertEquals(operations.size, operations.map { it.operationId }.toSet().size)
    }
}
