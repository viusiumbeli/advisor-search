package com.advisorsearch.documents

import com.advisorsearch.IntegrationTest
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transactional for the reason given on SchemaConstraintTest. It matters most here: the forty
 * paragraphs of filler below embed into a document that outscores real ones, and the semantic floor
 * is relative, so leaving it behind does not merely add a row to another class's results — it can
 * lift the cut-off past the document that class is asserting on.
 */
@Transactional
class DocumentApiTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) : IntegrationTest() {
    @Test
    fun `an unknown client is rejected before anything is embedded`() {
        val before = chunkCount()

        mockMvc
            .post("/clients/${UUID.randomUUID()}/documents") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"title":"Orphan","content":"This should never be embedded."}"""
            }.andExpect {
                status { isNotFound() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            }

        assertEquals(before, chunkCount(), "an unknown client must not cost any model inference")
    }

    @Test
    fun `content beyond the cap is rejected with a message naming the cap`() {
        val clientId = createClient()

        mockMvc
            .post("/clients/$clientId/documents") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapperContent(
                        "Too long",
                        "x".repeat(100_001),
                    )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value(containsString("100000")) }
            }
    }

    @Test
    fun `a title of Unicode whitespace is rejected before anything is embedded`() {
        val clientId = createClient()
        val before = chunkCount()

        // A non-breaking space satisfies @NotBlank, whose trim() strips only characters up to
        // U+0020, and is then trimmed away on the way to the insert. Left to the database this is a
        // CHECK violation — a 500, paid for after chunking and inference.
        mockMvc
            .post("/clients/$clientId/documents") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapperContent("\u00A0", "Content that is perfectly fine.")
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value(containsString("title")) }
            }

        assertEquals(before, chunkCount(), "a rejected document must not cost any model inference")
    }

    @Test
    fun `a document is searchable as soon as it is created`() {
        val clientId = createClient()
        val marker = "Hallowfield ${UUID.randomUUID().toString().take(8)}"

        val location =
            mockMvc
                .post("/clients/$clientId/documents") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        objectMapperContent(
                            "Statement of Account",
                            "This statement relates to the $marker account and the balance carried forward.",
                        )
                }.andExpect {
                    status { isCreated() }
                    jsonPath("$.client_id") { value(clientId) }
                }.andReturn()
                .response
                .getHeader("Location")!!

        // No indexing delay to wait out: the 201 already means chunked and embedded, and the search
        // below reads it back through the same transaction that wrote it.
        mockMvc.get("/search") { param("q", marker) }.andExpect {
            status { isOk() }
            jsonPath("$[?(@.type == 'document')].document.title") {
                value(hasItem("Statement of Account"))
            }
        }

        mockMvc.get(location).andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Statement of Account") }
            jsonPath("$.content") { value(containsString(marker)) }
        }
    }

    @Test
    fun `every chunk of a long document records the configured model`() {
        val clientId = createClient()
        val paragraphs =
            (1..40).joinToString("\n\n") {
                "Paragraph $it of the review sets out the position on charges, the rebalancing " +
                    "carried out during the period, and the agreed actions for the year ahead."
            }

        val location =
            mockMvc
                .post("/clients/$clientId/documents") {
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapperContent("Long Review", paragraphs)
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response
                .getHeader("Location")!!

        val documentId = UUID.fromString(location.substringAfterLast('/'))
        val models =
            jdbc
                .sql("SELECT DISTINCT embedding_model FROM document_chunks WHERE document_id = :id")
                .param("id", documentId)
                .query(String::class.java)
                .list()
        val chunks =
            jdbc
                .sql("SELECT count(*) FROM document_chunks WHERE document_id = :id")
                .param("id", documentId)
                .query(Int::class.java)
                .single()

        assertTrue(chunks > 1, "expected the long document to be split, got $chunks chunk(s)")
        assertEquals(listOf("all-MiniLM-L6-v2"), models)
    }

    @Test
    fun `an unknown document returns 404`() {
        mockMvc.get("/documents/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
    }

    private fun chunkCount(): Int = jdbc.sql("SELECT count(*) FROM document_chunks").query(Int::class.java).single()

    private fun createClient(): String {
        val response =
            mockMvc
                .post("/clients") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """
                        {"first_name":"Doc","last_name":"Owner","email":"owner.${UUID.randomUUID()}@example.com"}
                        """.trimIndent()
                }.andReturn()
                .response
                .contentAsString
        return objectMapper.readTree(response).path("id").asString()
    }

    /** Builds a JSON body safely, so content with quotes or newlines cannot break the request. */
    private fun objectMapperContent(
        title: String,
        content: String,
    ): String = objectMapper.writeValueAsString(mapOf("title" to title, "content" to content))
}
