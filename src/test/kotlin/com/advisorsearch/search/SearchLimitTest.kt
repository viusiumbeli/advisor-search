package com.advisorsearch.search

import com.advisorsearch.IntegrationTest
import com.advisorsearch.config.SearchProperties
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The clamp on `limit` cannot be proved against the seeded corpus: a dozen clients fit under every
 * limit, so a clamped query and an unbounded one return the same rows and an assertion on the count
 * holds however the clamp behaves. These tests insert more rows than the ceiling — clients, because
 * they cost no inference — and roll them back, for the reason given on SchemaConstraintTest.
 */
@Transactional
class SearchLimitTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val properties: SearchProperties,
) : IntegrationTest() {
    /** Random, so the query reaches these clients and nothing else in the shared database. */
    private val marker = "clamp${UUID.randomUUID().toString().take(8)}"

    @Test
    fun `a limit above the configured maximum is clamped to it`() {
        insertMatchingClients(properties.maxLimit + 5)

        assertEquals(properties.maxLimit, clientHits(limit = 5_000))
    }

    @Test
    fun `a limit below one is raised to one`() {
        insertMatchingClients(3)

        assertEquals(1, clientHits(limit = 0))
        assertEquals(1, clientHits(limit = -10))
    }

    @Test
    fun `no limit at all falls back to the configured default`() {
        insertMatchingClients(properties.defaultLimit + 5)

        assertEquals(properties.defaultLimit, clientHits(limit = null))
    }

    private fun insertMatchingClients(count: Int) {
        repeat(count) { index ->
            jdbc
                .sql("INSERT INTO clients (first_name, last_name, email) VALUES ('Limit', 'Probe', :email)")
                .param("email", "$marker.$index@example.com")
                .update()
        }
    }

    private fun clientHits(limit: Int?): Int {
        val response =
            mockMvc
                .get("/search") {
                    param("q", marker)
                    limit?.let { param("limit", it.toString()) }
                }.andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString

        return objectMapper.readValue<List<Map<String, Any>>>(response).count { it["type"] == "client" }
    }
}
