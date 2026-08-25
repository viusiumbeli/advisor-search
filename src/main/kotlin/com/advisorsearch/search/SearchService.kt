package com.advisorsearch.search

import com.advisorsearch.config.SearchProperties
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.search.expansion.QueryExpander
import com.advisorsearch.search.ranking.FusedDocument
import com.advisorsearch.search.ranking.RankedList
import com.advisorsearch.search.ranking.ReciprocalRankFusion
import com.advisorsearch.support.WHITESPACE_RUN
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger(SearchService::class.java)

private const val SNIPPET_LENGTH = 240
private const val KEYWORD = "keyword"
private const val SEMANTIC = "semantic"

/** Most to least corroborated, used only to break exact fusion ties. */
private val EVIDENCE_ORDER = listOf("both", KEYWORD, SEMANTIC)

@Service
class SearchService(
    private val clients: ClientSearchRepository,
    private val documents: DocumentSearchRepository,
    private val embeddings: EmbeddingService,
    private val expander: QueryExpander,
    private val properties: SearchProperties,
) {
    fun search(
        rawQuery: String,
        requestedLimit: Int?,
    ): List<SearchHit> {
        val query = rawQuery.trim()
        val limit = (requestedLimit ?: properties.defaultLimit).coerceIn(1, properties.maxLimit)
        val started = TimeSource.Monotonic.markNow()

        val clientHits = findClients(query, limit)
        val keyword = findLexically(query)
        val semantic = findSemantically(query)
        val documentHits = fuse(keyword, semantic, limit)

        log.info(
            "search q='{}' clients={} keyword={} semantic={} returned={} in {}",
            query,
            clientHits.size,
            keyword.size,
            semantic.size,
            documentHits.size,
            started.elapsedNow(),
        )
        return clientHits + documentHits
    }

    private fun findClients(
        query: String,
        limit: Int,
    ): List<ClientHit> {
        // Trigram similarity over one or two characters is noise, so short queries keep the
        // substring arm only.
        val fuzzy = query.count(Char::isLetterOrDigit) >= properties.minFuzzyQueryLength
        return clients
            .search(query.lowercase(), limit, fuzzy, properties.wordSimilarityThreshold)
            .map { ClientHit(score = it.score.asScore(), matchedOn = it.matchedOn, client = it.client) }
    }

    /**
     * The lexical arm, floored relative to this query's best hit rather than absolutely: `ts_rank_cd`
     * has no scale that carries between queries. Calibrated in docs/search-design.md, "Calibrating
     * the cut-offs".
     */
    private fun findLexically(query: String): List<DocumentMatch> {
        val matches = documents.keywordSearch(query, properties.candidateDocuments)
        val best = matches.maxOfOrNull { it.score } ?: return emptyList()
        return matches.filter { it.score >= best * properties.keywordFloorRatio }
    }

    /**
     * Runs the semantic arm once per probe and keeps each document's best result across all of them.
     * The maximum, not a blend: averaging "address proof" with "utility bill" gives a vector that
     * matches both worse than either does alone.
     */
    private fun findSemantically(query: String): List<DocumentMatch> {
        // All probes are embedded in one padded batch — one forward pass, not one per probe.
        val best =
            embeddings
                .embedQueries(expander.expand(query))
                .flatMap { vector -> documents.semanticSearch(vector, properties.candidateDocuments) }
                .bestByDocument()

        // Both floors apply here, while the numbers are still cosine similarities: after fusion
        // there are only ranks, and "not similar enough" cannot be expressed as a rank.
        val ceiling = best.firstOrNull()?.score ?: 0.0
        val floor = maxOf(properties.semanticFloor, ceiling * properties.semanticFloorRatio)
        return best.filter { it.score >= floor }
    }

    private fun fuse(
        keyword: List<DocumentMatch>,
        semantic: List<DocumentMatch>,
        limit: Int,
    ): List<DocumentHit> {
        val byId = (semantic + keyword).associateBy { it.reference.id }
        val fused =
            ReciprocalRankFusion.fuse(
                listOf(
                    RankedList(KEYWORD, keyword.map { it.reference.id }),
                    RankedList(SEMANTIC, semantic.map { it.reference.id }),
                ),
                properties.rrfK,
            )

        // Exact ties are common: two documents that placed equally in different lists score
        // identically. Evidence breaks them first — a lexical hit is a fact, the token is in the
        // document, while a semantic hit is an estimate — then title, and only then id, which is
        // there to make the order stable rather than to mean anything. Deployments do not share
        // ids, so anything that reordered on id would rank differently from one install to the
        // next. Sorting uses the full-precision score; rounding is presentation and would
        // otherwise manufacture extra ties.
        val headlines = keyword.associate { it.reference.id to it.snippet }
        return fused
            .map { item ->
                val match = byId.getValue(item.id)
                Triple(item, match, if (item.sources.size > 1) "both" else item.sources.first())
            }.sortedWith(
                compareByDescending<Triple<FusedDocument, DocumentMatch, String>> { it.first.score }
                    .thenBy { EVIDENCE_ORDER.indexOf(it.third) }
                    .thenBy { it.second.reference.title }
                    .thenBy { it.second.reference.id },
            ).take(limit)
            .map { (item, match, matchedOn) ->
                DocumentHit(
                    score = item.score.asScore(),
                    matchedOn = matchedOn,
                    // A keyword hit carries a ts_headline built around the query terms, which reads
                    // better than a whole chunk; a semantic-only hit falls back to its best passage.
                    snippet = headlines[item.id]?.takeIf { it.isNotBlank() } ?: abbreviate(match.snippet),
                    document = match.reference,
                )
            }
    }

    private fun abbreviate(text: String): String {
        val collapsed = text.replace(WHITESPACE_RUN, " ").trim()
        if (collapsed.length <= SNIPPET_LENGTH) return collapsed
        val cut = collapsed.lastIndexOf(' ', SNIPPET_LENGTH).takeIf { it > SNIPPET_LENGTH / 2 } ?: SNIPPET_LENGTH
        return collapsed.take(cut).trimEnd(',', '.', ';') + "…"
    }
}
