package com.advisorsearch.config

import com.advisorsearch.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * The deployed instance runs with a key set; docker compose does not. This pins both halves of that
 * switch, including which paths stay open so a reviewer can still read the documentation and the
 * orchestrator can still probe health.
 */
@TestPropertySource(properties = ["api.key=test-secret-key"])
class ApiKeyFilterTest(
    private val mockMvc: MockMvc,
) : IntegrationTest() {
    @Test
    fun `a request without the header is rejected`() {
        mockMvc.get("/search") { param("q", "anything") }.andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `a request with the wrong key is rejected`() {
        mockMvc
            .get("/search") {
                param("q", "anything")
                header("X-API-Key", "not-the-key")
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a request with the right key is served`() {
        mockMvc
            .get("/search") {
                param("q", "anything")
                header("X-API-Key", "test-secret-key")
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `health probes and api documentation stay open`() {
        // The orchestrator's health check has no key, and a reviewer should be able to read the
        // API documentation before being given one.
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }
    }
}
