package com.advisorsearch.search

import com.advisorsearch.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The executable version of the argument for using trigrams on clients rather than full-text search.
 *
 * The task's own example is that a fragment of an email domain must find its client. Postgres's
 * English parser classifies an email address as one `email` token and emits it as a single lexeme,
 * so no full-text query for a fragment of the domain can match it. This test pins that behaviour
 * rather than leaving it as a claim in the README.
 */
class FtsEmailTokenisationProofTest(
    private val jdbc: JdbcClient,
) : IntegrationTest() {
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
        val score =
            jdbc
                .sql(
                    """
                    SELECT GREATEST(
                               CASE WHEN lower(:email) LIKE '%' || :query || '%' ESCAPE '\' THEN 1.0 ELSE 0.0 END,
                               word_similarity(:query, lower(:email)))
                    """.trimIndent(),
                ).param("email", email)
                .param("query", "aldgatewealth")
                .query(Double::class.java)
                .single()

        assertEquals(1.0, score, "the substring arm should score an exact containment at 1.0")
    }

    private fun ftsMatches(query: String): Boolean =
        jdbc
            .sql("SELECT to_tsvector('english', :email) @@ websearch_to_tsquery('english', :query)")
            .param("email", email)
            .param("query", query)
            .query(Boolean::class.java)
            .single()
}
