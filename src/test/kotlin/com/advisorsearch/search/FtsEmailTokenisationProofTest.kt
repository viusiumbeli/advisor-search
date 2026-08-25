package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.config.SearchProperties
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The executable version of the argument for trigrams over full-text search: Postgres classifies an
 * email address as one `email` token and emits it as a single lexeme, so no full-text query for a
 * fragment of the domain can match it — and the task's first example would fail.
 */
class FtsEmailTokenisationProofTest(
    private val jdbc: JdbcClient,
    private val clients: ClientSearchRepository,
    private val properties: SearchProperties,
) : SeededIntegrationTest() {
    private val email = "jane.roe@aldgatewealth.example"

    @Test
    fun `an email address becomes a single lexeme`() {
        val lexemes =
            jdbc
                .sql("SELECT to_tsvector('english', :email)::text")
                .param("email", email)
                .query(String::class.java)
                .single()

        assertEquals("'$email':1", lexemes)
    }

    @Test
    fun `a full text query cannot match inside that lexeme`() {
        val matched = ftsMatches("AldgateWealth")
        val fullDomainMatched = ftsMatches("aldgatewealth.example")

        assertFalse(matched, "full-text search unexpectedly matched a fragment of the email")
        assertFalse(fullDomainMatched, "full-text search unexpectedly matched the bare domain")
    }

    @Test
    fun `normalising the email first would still only match whole tokens`() {
        // The obvious rescue is to strip the punctuation before indexing. It is IMMUTABLE, so it
        // is legal in a generated column, and it does match the whole domain. It still cannot
        // match "AldgateW" or a misspelling, which is why it is not the design chosen here.
        val wholeToken =
            jdbc
                .sql(
                    """
                    SELECT to_tsvector('english', translate(:email, '@.', '  '))
                               @@ websearch_to_tsquery('english', 'aldgatewealth')
                    """.trimIndent(),
                ).param("email", email)
                .query(Boolean::class.java)
                .single()

        val partialToken =
            jdbc
                .sql(
                    """
                    SELECT to_tsvector('english', translate(:email, '@.', '  '))
                               @@ websearch_to_tsquery('english', 'aldgatew')
                    """.trimIndent(),
                ).param("email", email)
                .query(Boolean::class.java)
                .single()

        assertTrue(wholeToken, "normalisation should reach the whole domain token")
        assertFalse(partialToken, "normalisation should still not reach a partial token")
    }

    @Test
    fun `the shipped trigram predicate does match the same email`() {
        // Through the repository rather than a restatement of its SQL. A proof written as a copy
        // proves the copy: it would stay green while the shipped statement lost its `lower()` or
        // its ESCAPE clause, and every email in this suite is stored lowercase, so nothing else
        // would notice either.
        val matches =
            clients.search(
                query = "aldgatewealth",
                limit = 10,
                fuzzy = true,
                wordSimilarityThreshold = properties.wordSimilarityThreshold,
            )

        val hit = matches.single { it.client.email == email }
        assertEquals(1.0, hit.score, "the substring arm should score an exact containment at 1.0")
        assertEquals("email", hit.matchedOn, "the containment is inside the email address")
    }

    private fun ftsMatches(query: String): Boolean =
        jdbc
            .sql("SELECT to_tsvector('english', :email) @@ websearch_to_tsquery('english', :query)")
            .param("email", email)
            .param("query", query)
            .query(Boolean::class.java)
            .single()
}
