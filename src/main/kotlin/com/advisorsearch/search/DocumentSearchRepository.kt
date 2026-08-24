package com.advisorsearch.search

import com.advisorsearch.support.toVectorLiteral
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Hand-mapped because the row feeds a nested reference plus score and snippet. */
private val documentMatchRowMapper =
    RowMapper { row, _ ->
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

@Repository
class DocumentSearchRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * The lexical arm, which finds the rare exact tokens embeddings treat as noise — a policy number,
     * a reference code. `websearch_to_tsquery` combines terms with AND, so this arm legitimately
     * returns nothing for many queries; that is why fusion is a union and not a join.
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
            .query(documentMatchRowMapper)
            .list()

    /**
     * The semantic arm. The inner query takes the globally nearest chunks and `DISTINCT ON` keeps each
     * document's best one, which is both its score and its snippet. The scan is exact, not approximate
     * — see docs/operating-notes.md, "There is deliberately no ANN index".
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
            .query(documentMatchRowMapper)
            .list()
            // DISTINCT ON has to lead with the grouping column, so the rows come back ordered by
            // document id. Relevance order is restored here.
            .sortedWith(compareByDescending<DocumentMatch> { it.score }.thenBy { it.reference.id })
}
