package com.advisorsearch.search

import com.advisorsearch.clients.toClient
import com.advisorsearch.support.escapeLikeWildcards
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Builds the one client search statement from its two variable parts. Only compile-time constants are
 * interpolated; user input reaches the statement exclusively through named parameters.
 *
 * `matched_on` names a field only when the query literally occurs in it — a fuzzy hit is scored over
 * the whole profile, so no single field is responsible and it reports `profile`.
 */
private fun clientSearchSql(
    score: String,
    predicate: String,
) = """
    SELECT id, first_name, last_name, email, description, social_links,
           $score AS score,
           CASE
               WHEN lower(first_name || ' ' || last_name) LIKE :pattern ESCAPE '\' THEN 'name'
               WHEN lower(email) LIKE :pattern ESCAPE '\' THEN 'email'
               WHEN lower(coalesce(description, '')) LIKE :pattern ESCAPE '\' THEN 'description'
               ELSE 'profile'
           END AS matched_on
    FROM clients
    WHERE $predicate
    ORDER BY score DESC, last_name, first_name, id
    LIMIT :limit
    """.trimIndent()

private const val SUBSTRING_SCORE = "CASE WHEN search_text LIKE :pattern ESCAPE '\\' THEN 1.0 ELSE 0.0 END"

private val SUBSTRING_ONLY = clientSearchSql(SUBSTRING_SCORE, "search_text LIKE :pattern ESCAPE '\\'")

private val SUBSTRING_AND_FUZZY =
    clientSearchSql(
        "GREATEST($SUBSTRING_SCORE, word_similarity(:query, search_text))",
        "search_text LIKE :pattern ESCAPE '\\' OR :query <% search_text",
    )

/**
 * Trigrams rather than full-text search: Postgres tokenises an email address into a single `email`
 * lexeme, so no full-text query for a fragment of the domain can match inside it.
 * `FtsEmailTokenisationProofTest` pins that.
 */
@Repository
class ClientSearchRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * Both arms in one statement: `LIKE '%q%'` guarantees a literal match scores 1.0, and `<%` is
     * pg_trgm's word-similarity arm for typos. One `gin_trgm_ops` index serves both.
     */
    @Transactional(readOnly = true)
    fun search(
        query: String,
        limit: Int,
        fuzzy: Boolean,
        wordSimilarityThreshold: Double,
    ): List<ClientMatch> {
        if (fuzzy) {
            // Transaction-local so the setting cannot leak to the next borrower of this connection.
            jdbc
                .sql("SELECT set_config('pg_trgm.word_similarity_threshold', :threshold, true)")
                .param("threshold", wordSimilarityThreshold.toString())
                .query(String::class.java)
                .single()
        }

        return jdbc
            .sql(if (fuzzy) SUBSTRING_AND_FUZZY else SUBSTRING_ONLY)
            .param("pattern", "%" + query.escapeLikeWildcards() + "%")
            .param("query", query)
            .param("limit", limit)
            .query { row, _ ->
                ClientMatch(
                    client = row.toClient(),
                    score = row.getDouble("score"),
                    matchedOn = row.getString("matched_on"),
                )
            }.list()
    }
}
