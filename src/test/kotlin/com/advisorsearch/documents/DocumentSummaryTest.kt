package com.advisorsearch.documents

import com.advisorsearch.SeededIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentSummaryTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val service: DocumentService,
) : SeededIntegrationTest() {
    @Test
    fun `summarises a long document with passages in reading order`() {
        val id = documentTitled("Trust Deed Summary and Trustee Responsibilities")

        val summary = service.summarise(id)

        assertEquals(3, summary.passages.size)
        assertEquals(
            summary.passages.map { it.chunkIndex }.sorted(),
            summary.passages.map { it.chunkIndex },
            "passages must be returned in the order they appear in the document, not by score",
        )
        summary.passages.forEach { passage ->
            assertTrue(passage.text.isNotBlank())
            assertTrue(passage.centrality > 0.0, "centrality should be a cosine similarity")
        }
        assertTrue(summary.chunkCount > summary.passages.size)
    }

    @Test
    fun `every passage is verbatim from the document`() {
        val id = documentTitled("Suitability Report: Pension Consolidation")
        val content = service.get(id).content

        // The summary is extractive: nothing in it may be invented.
        service.summarise(id).passages.forEach { passage ->
            assertTrue(
                content.contains(passage.text),
                "passage is not present verbatim in the document: ${passage.text.take(60)}…",
            )
        }
    }

    @Test
    fun `a short document summarises to its own chunks`() {
        val id = documentTitled("ISA Transfer Confirmation")

        val summary = service.summarise(id)

        assertTrue(summary.passages.size <= summary.chunkCount)
        assertTrue(summary.passages.isNotEmpty())
    }

    @Test
    fun `the endpoint returns the summary and 404s for an unknown id`() {
        val id = documentTitled("Buy-to-Let Portfolio Review")

        mockMvc.get("/documents/$id/summary").andExpect {
            status { isOk() }
            jsonPath("$.document_id") { value(id.toString()) }
            jsonPath("$.title") { value("Buy-to-Let Portfolio Review") }
            jsonPath("$.passages.length()") { value(3) }
            jsonPath("$.passages[0].chunk_index") { exists() }
        }

        mockMvc.get("/documents/${UUID.randomUUID()}/summary").andExpect { status { isNotFound() } }
    }

    private fun documentTitled(title: String): UUID =
        jdbc
            .sql("SELECT id FROM documents WHERE title = :title")
            .param("title", title)
            .query(UUID::class.java)
            .single()
}
