package com.advisorsearch.search

import com.advisorsearch.clients.toClient
import com.advisorsearch.support.escapeLikeWildcards
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Builds the one client search statement from its two variable parts. Only compile-time constants
 * are ever interpolated here; user input reaches the statement exclusively through the named
 * parameters.
 *
 * `matched_on` names a field only when the query literally occurs in it. A fuzzy hit has no single
 * responsible field — the similarity is computed over the whole profile — so it reports `profile`
 * rather than inventing an attribution.
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
 * Clients are found with trigrams, not full-text search.
 *
 * Postgres tokenises `jane.roe@aldgatewealth.example` into a single `email` lexeme, so a full-text
 * query for "AldgateWealth" can never match inside it — the task's own example would fail. Trigrams
 * index three-character shingles of the whole profile, so a substring of an email, a misspelled
 * surname and a word from a description are all reachable through one index.
 */
@Repository
class ClientSearchRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * Runs both arms in one statement. `LIKE '%q%'` is the exact-substring arm that guarantees a
     * literal match scores 1.0; `<%` is pg_trgm's word-similarity arm that tolerates typos. A single
     * `gin_trgm_ops` index serves both, and the planner combines them with a BitmapOr.
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
