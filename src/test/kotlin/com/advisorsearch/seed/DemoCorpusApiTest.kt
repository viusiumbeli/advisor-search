package com.advisorsearch.seed

import com.advisorsearch.IntegrationTest
import com.advisorsearch.clients.ClientService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transactional for two reasons. The usual one, from SchemaConstraintTest: the suite shares one
 * database and residue displaces other classes' rankings. The specific one: testing the success path
 * needs an *empty* corpus, and by the time this class runs the seeded classes have filled it — so it
 * empties the tables and rolls that back, which is the only way to get there without depending on
 * test order or forking the Spring context for a second container.
 */
@Transactional
class DemoCorpusApiTest(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val clients: ClientService,
) : IntegrationTest() {
    @Test
    fun `refuses an instance that already holds data`() {
        insertClient()
        val before = documentCount()

        mockMvc.post("/demo-corpus").andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value(containsString("empty instance")) }
        }

        assertEquals(before, documentCount(), "a refused load must not have embedded anything")
    }

    @Test
    fun `loads the corpus into an empty instance and reports what it created`() {
        emptyTheCorpus()

        mockMvc.post("/demo-corpus").andExpect {
            status { isCreated() }
            jsonPath("$.clients_created") { value(10) }
            jsonPath("$.documents_created") { value(20) }
            jsonPath("$.skipped") { value(0) }
        }

        // The point of the endpoint is not twenty rows, it is twenty *searchable* documents — and
        // this query is the one the console uses to decide whether to show the brief's examples.
        mockMvc.get("/search") { param("q", "Marlow Court") }.andExpect {
            status { isOk() }
            jsonPath("$[?(@.type == 'document')].document.title") {
                value(hasItem(containsString("Electricity Account Statement")))
            }
        }
    }

    @Test
    fun `emptiness is decided by whether any client exists`() {
        emptyTheCorpus()
        assertFalse(clients.anyExist(), "the corpus should be empty after deleting every client")

        insertClient()

        assertTrue(clients.anyExist(), "one client is enough to count as data worth protecting")
    }

    /** Documents and chunks reference a client, so deleting clients cascades the whole corpus away. */
    private fun emptyTheCorpus() {
        jdbc.sql("DELETE FROM clients").update()
        assertEquals(0, documentCount(), "the cascade should have taken the documents with the clients")
        assertEquals(
            0,
            jdbc.sql("SELECT count(*) FROM document_chunks").query(Int::class.java).single(),
            "and their chunks, or the assertions below would be running against a corpus still in place",
        )
    }

    private fun documentCount(): Int = jdbc.sql("SELECT count(*) FROM documents").query(Int::class.java).single()

    private fun insertClient() {
        jdbc
            .sql("INSERT INTO clients (first_name, last_name, email) VALUES ('Own', 'Data', :email)")
            .param("email", "own.data.${UUID.randomUUID()}@example.com")
            .update()
    }
}
