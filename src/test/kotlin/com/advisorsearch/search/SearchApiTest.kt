package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchApiTest(
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
    fun `document hits name the retrievers that found them`() {
        val arms = setOf("keyword", "sparse", "semantic")

        listOf("address proof", "PLC-88213", "trustee duties", "double taxation treaty").forEach { query ->
            val hits = search(query).filter { it["type"] == "document" }
            assertTrue(hits.isNotEmpty(), "expected document hits for '$query'")
            hits.forEach { hit ->
                val matchedOn = hit["matched_on"] as String
                val sources = (hit["sources"] as List<*>).map { it as String }
                assertTrue(sources.isNotEmpty() && sources.all { it in arms }, "sources must name retrievers, got $sources")
                assertEquals(sources.distinct(), sources, "a retriever is listed once")
                if (sources.size > 1) assertEquals("multiple", matchedOn) else assertEquals(sources.single(), matchedOn)
            }
        }
    }

    @Test
    fun `a reference code still puts the policy schedule first`() {
        val titles = search("PLC-88213").filter { it["type"] == "document" }.map { title(it) }

        assertTrue(titles.first().contains("Policy Schedule"), "got $titles")
    }

    @Test
    fun `a blank query is rejected as problem json`() {
        // Rendered by built-in method validation on the @NotBlank controller parameter.
        mockMvc.get("/search") { param("q", "   ") }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
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

        return objectMapper.readValue<List<Map<String, Any>>>(response)
    }

    @Suppress("UNCHECKED_CAST")
    private fun document(hit: Map<String, Any>) = hit["document"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun client(hit: Map<String, Any>) = hit["client"] as Map<String, Any>

    private fun title(hit: Map<String, Any>) = document(hit)["title"] as String
}
