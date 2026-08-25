package com.advisorsearch.config

import com.advisorsearch.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * The deployed instance runs with a key set, docker compose does not. Pins both halves of that
 * switch, including which paths stay open.
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
    fun `the demo corpus loader is data like anything else`() {
        // It is registered unconditionally rather than behind a profile, so on a keyed instance the
        // key is the only thing standing between a stranger and ten clients they did not ask for.
        mockMvc.post("/demo-corpus").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `health probes and api documentation stay open`() {
        // The orchestrator's health check has no key, and a reviewer should be able to read the
        // API documentation before being given one.
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }
    }

    @Test
    fun `the console page stays open but the API it calls does not`() {
        // A browser cannot attach X-API-Key to a plain navigation, so the page itself must load
        // without one; the data it fetches is still keyed, as the 401 tests above prove.
        // MockMvc reports the welcome page as the forward it is rather than following it.
        mockMvc.get("/").andExpect {
            status { isOk() }
            forwardedUrl("index.html")
        }
        mockMvc.get("/index.html").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
    }
}
