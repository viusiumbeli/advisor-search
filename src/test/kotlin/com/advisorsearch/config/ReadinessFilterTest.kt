package com.advisorsearch.config

import org.junit.jupiter.api.Test
import org.springframework.boot.availability.ApplicationAvailabilityBean
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deliberately without a Spring context: the window this filter exists for is the one before the
 * context is ready, and a context that is running has already left it. The availability bean starts
 * in the state Spring Boot leaves it in until the last runner returns.
 */
class ReadinessFilterTest {
    private val availability = ApplicationAvailabilityBean()
    private val filter = ReadinessFilter(availability, JsonMapper.builder().build())

    @Test
    fun `a data request before readiness is refused, and never reaches the application`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(get("/search"), response, chain)

        assertEquals(503, response.status)
        assertNull(chain.request, "a write in this window can commit vectors the model check has not passed yet")
        assertTrue(response.contentType!!.startsWith("application/problem+json"), "got ${response.contentType}")
        assertEquals("5", response.getHeader("Retry-After"))
        assertTrue(response.contentAsString.contains("/actuator/health/readiness"), "the body should say what to poll")
    }

    @Test
    fun `the probes, the documentation and the console stay reachable while starting`() {
        // The readiness probe above all: gating it would leave the orchestrator waiting on a signal
        // that could never arrive.
        listOf("/actuator/health/readiness", "/actuator/health", "/", "/index.html", "/swagger-ui.html", "/v3/api-docs")
            .forEach { path ->
                val response = MockHttpServletResponse()

                filter.doFilter(get(path), response, MockFilterChain())

                assertEquals(200, response.status, "$path must not be held back by readiness")
            }
    }

    @Test
    fun `everything is served once the runners have finished`() {
        availability.onApplicationEvent(AvailabilityChangeEvent(this, ReadinessState.ACCEPTING_TRAFFIC))
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(get("/search"), response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request, "the request should have been passed along")
    }

    private fun get(path: String) = MockHttpServletRequest("GET", path)
}
