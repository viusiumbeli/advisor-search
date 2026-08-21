package com.advisorsearch.documents

import com.advisorsearch.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentApiTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val jdbc: JdbcClient,
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
                    jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("100000")) }
                }
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

            // No indexing delay to wait out: the 201 already means chunked, embedded and committed.
            mockMvc.get("/search") { param("q", marker) }.andExpect {
                status { isOk() }
                jsonPath("$[?(@.type == 'document')].document.title") {
                    value(org.hamcrest.Matchers.hasItem("Statement of Account"))
                }
            }

            mockMvc.get(location).andExpect {
                status { isOk() }
                jsonPath("$.title") { value("Statement of Account") }
                jsonPath("$.content") { value(org.hamcrest.Matchers.containsString(marker)) }
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

        private fun createClient(): String =
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
                .substringAfter("\"id\":\"")
                .substringBefore('"')

        /** Builds a JSON body safely, so content with quotes or newlines cannot break the request. */
        private fun objectMapperContent(
            title: String,
            content: String,
        ): String =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .writeValueAsString(mapOf("title" to title, "content" to content))
    }
