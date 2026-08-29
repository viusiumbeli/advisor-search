package com.advisorsearch.search

import com.advisorsearch.embedding.SparseVector
import com.advisorsearch.support.toSparseVectorLiteral
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
     * The semantic arm. Every document is scored by its own nearest chunk, and the best
     * [candidateDocuments] of *those* are shortlisted — so the shortlist is bounded by documents: the
     * unit the lexical arm is bounded by, and the unit fusion ranks in.
     *
     * Bounding it by chunks instead is the subtle version of this query, and what it costs is recall
     * that never shows up as an error. The seeded reports run to 22 chunks each, so two or three of
     * them fill a 50-chunk window; whatever does not fit is not ranked low, it is not ranked at all,
     * and the documents most easily squeezed out are the short ones — the electricity bill the
     * brief's own example turns on is three chunks long.
     *
     * Written as an aggregate rather than the `DISTINCT ON` that reads more naturally, because
     * `min()` groups through a hash table while `DISTINCT ON` has to sort every chunk in the corpus:
     * 234 ms against 417 ms at 99,700 chunks. Only the thirty survivors then pay for their text.
     * The measurements are in docs/search-design.md.
     *
     * The scan stays exact rather than approximate — see docs/operating-notes.md, "There is
     * deliberately no ANN index". The shortlist's ORDER BY carries the document id as a tiebreaker:
     * exactly equal distances are rare for real vectors but routine once a corpus holds copies, and
     * without it which of the tied documents survive the LIMIT is whatever order the aggregate
     * happened to produce.
     */
    fun semanticSearch(
        queryVector: FloatArray,
        candidateDocuments: Int,
    ): List<DocumentMatch> =
        jdbc
            .sql(
                """
                WITH best AS (
                    SELECT document_id, min(embedding <=> CAST(:vector AS vector)) AS distance
                    FROM document_chunks
                    GROUP BY document_id
                    ORDER BY distance, document_id
                    LIMIT :candidates
                )
                SELECT d.id, d.client_id, d.title, d.created_at,
                       nearest.content AS snippet,
                       1 - best.distance AS score
                FROM best
                JOIN documents d ON d.id = best.document_id
                JOIN LATERAL (
                    SELECT content
                    FROM document_chunks
                    WHERE document_id = best.document_id
                    ORDER BY embedding <=> CAST(:vector AS vector), id
                    LIMIT 1
                ) nearest ON true
                ORDER BY best.distance, d.id
                """.trimIndent(),
            ).param("vector", queryVector.toVectorLiteral())
            .param("candidates", candidateDocuments)
            .query(documentMatchRowMapper)
            .list()

    /**
     * The sparse arm: the semantic arm's shape over learned term weights. `<#>` is pgvector's NEGATIVE
     * inner product, because index scans are ascending, so min() of it is a document's best chunk and
     * the score is its negation. A document sharing no term with the query sits at exactly 0 and would
     * otherwise fill the shortlist in id order — the HAVING keeps them out, so an empty result means
     * "nothing shares a term with this query" rather than "thirty ties". The score comes back in the
     * model's own units; the caller divides it by the query's mass before any floor sees it.
     *
     * Its scan is not the dense arm's with a different operator: sparsevec is stored out of line, so
     * every chunk is detoasted on the way past, and the merge is scalar where the dense kernel is
     * vectorised. Measured in docs/load-and-limits.md.
     */
    fun sparseSearch(
        queryVector: SparseVector,
        candidateDocuments: Int,
    ): List<DocumentMatch> =
        jdbc
            .sql(
                """
                WITH best AS (
                    SELECT document_id, min(sparse_embedding <#> CAST(:vector AS sparsevec)) AS distance
                    FROM document_chunks
                    GROUP BY document_id
                    HAVING min(sparse_embedding <#> CAST(:vector AS sparsevec)) < 0
                    ORDER BY distance, document_id
                    LIMIT :candidates
                )
                SELECT d.id, d.client_id, d.title, d.created_at,
                       nearest.content AS snippet,
                       -best.distance AS score
                FROM best
                JOIN documents d ON d.id = best.document_id
                JOIN LATERAL (
                    SELECT content
                    FROM document_chunks
                    WHERE document_id = best.document_id
                    ORDER BY sparse_embedding <#> CAST(:vector AS sparsevec), id
                    LIMIT 1
                ) nearest ON true
                ORDER BY best.distance, d.id
                """.trimIndent(),
            ).param("vector", queryVector.toSparseVectorLiteral())
            .param("candidates", candidateDocuments)
            .query(documentMatchRowMapper)
            .list()
}
