package com.advisorsearch.search

import com.advisorsearch.support.toVectorLiteral
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

data class DocumentMatch(
    val reference: DocumentReference,
    val score: Double,
    val snippet: String,
)

@Repository
class DocumentSearchRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * The lexical arm. Full-text search is what finds rare exact tokens — a policy number, a
     * surname, a reference code — which embeddings treat as noise because they carry no semantics.
     *
     * `websearch_to_tsquery` combines terms with AND, so a two-word query only matches documents
     * containing both. That is why fusion must be a union: for "address proof" this arm legitimately
     * returns nothing and the semantic arm carries the result.
     */
    fun keywordSearch(
        query: String,
        limit: Int,
    ): List<DocumentMatch> =
        jdbc
            .sql(
                """
                SELECT d.id, d.client_id, d.title, d.created_at,
                       ts_rank_cd(d.fts, q) AS score,
                       ts_headline('english', d.content, q,
                                   'StartSel="", StopSel="", MaxFragments=1, MaxWords=45, MinWords=25')
                           AS snippet
                FROM documents d, websearch_to_tsquery('english', :query) AS q
                WHERE d.fts @@ q
                ORDER BY score DESC, d.id
                LIMIT :limit
                """.trimIndent(),
            ).param("query", query)
            .param("limit", limit)
            .query(::mapMatch)
            .list()

    /**
     * The semantic arm, and the one that answers "address proof" with a utility bill.
     *
     * The scan is exact rather than approximate: at this corpus size a full cosine scan is
     * sub-millisecond, and an exact scan cannot miss a neighbour the way an ANN index can. The inner
     * query takes the globally nearest chunks; `DISTINCT ON` then keeps each document's single best
     * chunk, which is both the ranking score and the snippet.
     */
    fun semanticSearch(
        queryVector: FloatArray,
        candidateChunks: Int,
    ): List<DocumentMatch> =
        jdbc
            .sql(
                """
                SELECT DISTINCT ON (top.document_id)
                       top.document_id AS id, d.client_id, d.title, d.created_at,
                       top.content AS snippet,
                       1 - (top.embedding <=> CAST(:vector AS vector)) AS score
                FROM (
                    SELECT document_id, content, embedding
                    FROM document_chunks
                    ORDER BY embedding <=> CAST(:vector AS vector)
                    LIMIT :candidates
                ) top
                JOIN documents d ON d.id = top.document_id
                ORDER BY top.document_id, top.embedding <=> CAST(:vector AS vector)
                """.trimIndent(),
            ).param("vector", queryVector.toVectorLiteral())
            .param("candidates", candidateChunks)
            .query(::mapMatch)
            .list()
            // DISTINCT ON has to lead with the grouping column, so the rows come back ordered by
            // document id. Relevance order is restored here.
            .sortedWith(compareByDescending<DocumentMatch> { it.score }.thenBy { it.reference.id })

    private fun mapMatch(
        row: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): DocumentMatch =
        DocumentMatch(
            reference =
                DocumentReference(
                    id = row.getObject("id", UUID::class.java),
                    clientId = row.getObject("client_id", UUID::class.java),
                    title = row.getString("title"),
                    createdAt = row.getObject("created_at", OffsetDateTime::class.java),
                ),
            score = row.getDouble("score"),
            snippet = row.getString("snippet"),
        )
}
