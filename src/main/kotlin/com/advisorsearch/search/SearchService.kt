package com.advisorsearch.search

import com.advisorsearch.config.SearchProperties
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.support.InvalidRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

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
        if (query.isEmpty()) throw InvalidRequestException("q must contain at least one non-blank character")
        val limit = (requestedLimit ?: properties.defaultLimit).coerceIn(1, properties.maxLimit)
        val startedAt = System.nanoTime()

        val clientHits = findClients(query, limit)
        val keyword = findLexically(query)
        val semantic = findSemantically(query)
        val documentHits = fuse(keyword, semantic, limit)

        log.info(
            "search q='{}' clients={} keyword={} semantic={} returned={} in {}ms",
            query,
            clientHits.size,
            keyword.size,
            semantic.size,
            documentHits.size,
            (System.nanoTime() - startedAt) / 1_000_000,
        )
        return clientHits + documentHits
    }

    private fun findClients(
        query: String,
        limit: Int,
    ): List<ClientHit> {
        // Trigram similarity over one or two characters is noise, so short queries keep only the
        // exact-substring arm rather than returning an arbitrary slice of the client list.
        val fuzzy = query.count(Char::isLetterOrDigit) >= properties.minFuzzyQueryLength
        return clients
            .search(query.lowercase(), limit, fuzzy, properties.wordSimilarityThreshold)
            .map { ClientHit(score = it.score.asScore(), matchedOn = it.matchedOn, client = it.client) }
    }

    /**
     * The lexical arm, with a relevance floor expressed relative to the best hit for this query.
     *
     * `ts_rank_cd` has no absolute scale, so an absolute threshold would mean different things for
     * different queries, but within one query the numbers are comparable. On this corpus a genuine
     * secondary match scores about half the best hit while an incidental one — a document that
     * happens to contain the words "burden of proof" — scores under a twentieth of it. Without the
     * floor those incidental hits reach fusion and outrank real answers, because reciprocal rank
     * fusion sees their position in the list and not how weak they were.
     */
    private fun findLexically(query: String): List<DocumentMatch> {
        val matches = documents.keywordSearch(query, properties.candidateDocuments)
        val best = matches.maxOfOrNull { it.score } ?: return emptyList()
        return matches.filter { it.score >= best * properties.keywordFloorRatio }
    }

    /**
     * Runs the semantic arm once per probe and keeps each document's best result across all of them.
     *
     * Taking the maximum rather than blending the probes into one vector matters: averaging "address
     * proof" with "utility bill" produces a vector that is a weaker match for both than either is on
     * its own, whereas the maximum lets whichever phrasing actually describes the document win.
     */
    private fun findSemantically(query: String): List<DocumentMatch> {
        val probes = expander.expand(query)
        val best = LinkedHashMap<UUID, DocumentMatch>()
        for (probe in probes) {
            val vector = embeddings.embedQuery(probe)
            for (match in documents.semanticSearch(vector, properties.candidateChunks)) {
                val incumbent = best[match.reference.id]
                if (incumbent == null || match.score > incumbent.score) best[match.reference.id] = match
            }
        }
        // Both floors are applied here, while the numbers are still cosine similarities. After
        // fusion there are only ranks, and "not similar enough" cannot be expressed as a rank.
        //
        // The absolute floor decides whether the corpus can answer the query at all. The relative
        // one decides how many results are worth showing: cosine has no calibrated scale across
        // queries, but within one query the scores are comparable, so a document scoring well below
        // this query's best match is a loosely-related document rather than an answer.
        val ceiling = best.values.maxOfOrNull { it.score } ?: 0.0
        val floor = maxOf(properties.semanticFloor, ceiling * properties.semanticFloorRatio)
        return best.values
            .filter { it.score >= floor }
            .sortedWith(compareByDescending<DocumentMatch> { it.score }.thenBy { it.reference.id })
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
                    ReciprocalRankFusion.RankedList(KEYWORD, keyword.map { it.reference.id }),
                    ReciprocalRankFusion.RankedList(SEMANTIC, semantic.map { it.reference.id }),
                ),
                properties.rrfK,
            )

        // A keyword hit carries a ts_headline built around the query terms, which reads better than
        // a whole chunk; a semantic-only hit falls back to its best-matching passage.
        val headlines = keyword.associate { it.reference.id to it.snippet }
        return fused
            .map { item ->
                val match = byId.getValue(item.id)
                DocumentHit(
                    score = item.score.asScore(),
                    matchedOn = if (item.sources.size > 1) "both" else item.sources.first(),
                    snippet = headlines[item.id]?.takeIf { it.isNotBlank() } ?: abbreviate(match.snippet),
                    document = match.reference,
                )
            }
            // Exact ties are common: two documents that placed equally in different lists score
            // identically. They are broken by evidence first — a lexical hit is a fact, the token is
            // in the document, while a semantic hit is an estimate — and then by title. Neither
            // falls back to the primary key, because ids are random per install and would reorder
            // results between one deployment and the next.
            .sortedWith(
                compareByDescending<DocumentHit> { it.score }
                    .thenBy { EVIDENCE_ORDER.indexOf(it.matchedOn) }
                    .thenBy { it.document.title }
                    .thenBy { it.document.id },
            ).take(limit)
    }

    private fun abbreviate(text: String): String {
        val collapsed = text.replace(WHITESPACE, " ").trim()
        if (collapsed.length <= SNIPPET_LENGTH) return collapsed
        val cut = collapsed.lastIndexOf(' ', SNIPPET_LENGTH).takeIf { it > SNIPPET_LENGTH / 2 } ?: SNIPPET_LENGTH
        return collapsed.take(cut).trimEnd(',', '.', ';') + "…"
    }

    private companion object {
        val log = LoggerFactory.getLogger(SearchService::class.java)
        val WHITESPACE = Regex("\\s+")
        const val SNIPPET_LENGTH = 240
        const val KEYWORD = "keyword"
        const val SEMANTIC = "semantic"

        /** Most to least corroborated, used only to break exact fusion ties. */
        val EVIDENCE_ORDER = listOf("both", KEYWORD, SEMANTIC)
    }
}
