package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchApiTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
    ) : SeededIntegrationTest() {
        @Test
        fun `the task's client example returns the right client at score one`() {
            mockMvc.get("/search") { param("q", "AldgateWealth") }.andExpect {
                status { isOk() }
                jsonPath("$[0].type") { value("client") }
                jsonPath("$[0].score") { value(1.0) }
                jsonPath("$[0].matched_on") { value("email") }
                jsonPath("$[0].client.email") { value("jane.roe@aldgatewealth.example") }
            }
        }

        @Test
        fun `the task's document example returns the utility bill with no shared words`() {
            val titles = search("address proof").filter { it["type"] == "document" }.map { title(it) }

            assertTrue(
                titles.any { it.contains("Electricity Account Statement") },
                "the electricity bill should be reachable from \"address proof\"; got $titles",
            )
        }

        @Test
        fun `client hits come before document hits`() {
            val types = search("raghunathan").map { it["type"] as String }

            assertEquals(
                types.sortedBy { if (it == "client") 0 else 1 },
                types,
                "clients and documents must not be interleaved",
            )
        }

        @Test
        fun `each document appears at most once`() {
            val ids =
                search("pension")
                    .filter { it["type"] == "document" }
                    .map { document(it)["id"] as String }

            assertEquals(ids.distinct(), ids, "chunk over-fetching leaked duplicate documents")
        }

        @Test
        fun `a blank query is rejected as problem json`() {
            mockMvc.get("/search") { param("q", "   ") }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("non-blank")) }
            }
        }

        @Test
        fun `a missing query parameter is rejected`() {
            mockMvc.get("/search").andExpect { status { isBadRequest() } }
        }

        @Test
        fun `a two character query still matches by substring`() {
            // Below the fuzzy threshold, so only the exact-substring arm runs. It must still work.
            val emails =
                search("ro")
                    .filter { it["type"] == "client" }
                    .map { client(it)["email"] as String }

            assertTrue(emails.contains("jane.roe@aldgatewealth.example"), "got $emails")
        }

        @Test
        fun `a query that matches nothing returns an empty array`() {
            mockMvc.get("/search") { param("q", "qqzzx nonexistent gibberish") }.andExpect {
                status { isOk() }
                jsonPath("$") { isArray() }
                jsonPath("$.length()") { value(0) }
            }
        }

        @Test
        fun `limit is clamped to the configured maximum`() {
            val hits = search("the", limit = 5000)

            assertTrue(hits.size <= 100, "expected the limit to be clamped, got ${hits.size} hits")
        }

        @Test
        fun `wildcards in a query are treated as literal characters`() {
            // Unescaped, '%' would match every client in the table.
            val hits = search("%")

            assertTrue(hits.none { it["type"] == "client" }, "a bare wildcard must not match every client")
        }

        @Test
        fun `document hits carry a snippet and no full content`() {
            val hit = search("trustee duties").first { it["type"] == "document" }

            assertTrue((hit["snippet"] as String).isNotBlank())
            assertTrue("content" !in document(hit), "search results must not carry whole documents")
            assertTrue(document(hit).keys.containsAll(setOf("id", "client_id", "title", "created_at")))
        }

        private fun search(
            query: String,
            limit: Int? = null,
        ): List<Map<String, Any>> {
            val response =
                mockMvc
                    .get("/search") {
                        param("q", query)
                        limit?.let { param("limit", it.toString()) }
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response
                    .contentAsString

            @Suppress("UNCHECKED_CAST")
            return objectMapper.readValue(response, List::class.java) as List<Map<String, Any>>
        }

        @Suppress("UNCHECKED_CAST")
        private fun document(hit: Map<String, Any>) = hit["document"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        private fun client(hit: Map<String, Any>) = hit["client"] as Map<String, Any>

        private fun title(hit: Map<String, Any>) = document(hit)["title"] as String
    }
