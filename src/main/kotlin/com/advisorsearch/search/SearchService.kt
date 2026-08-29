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
import kotlin.time.measureTimedValue

private val log = LoggerFactory.getLogger(SearchService::class.java)

private const val SNIPPET_LENGTH = 240
private const val KEYWORD = "keyword"
private const val SPARSE = "sparse"
private const val SEMANTIC = "semantic"

/** What a document hit says when more than one retriever found it; `sources` then says which. */
private const val MULTIPLE = "multiple"

/**
 * Most to least literal, used to order `sources` and to break exact fusion ties. A keyword hit is the
 * token in the document; a sparse hit is the token, or a term the model learned the chunk implies,
 * in a chunk's representation; a semantic hit is an estimate of meaning.
 */
private val EVIDENCE_ORDER = listOf(KEYWORD, SPARSE, SEMANTIC)

/** Every retriever the fusion can name must be here: an unknown one would otherwise sort to the top of a tie. */
private fun evidenceRank(source: String): Int = EVIDENCE_ORDER.indexOf(source).also { check(it >= 0) { "Unknown retriever $source" } }

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
        val arms = documentArms(query)
        val documentHits = fuse(arms, limit)

        // Each arm's own time beside its count, so the cost of a retriever is a grep away.
        log.info(
            "search q='{}' clients={} keyword={} ({}) sparse={} ({}) semantic={} ({}) returned={} in {}",
            query,
            clientHits.size,
            arms.keyword.size,
            arms.keywordTime,
            arms.sparse.size,
            arms.sparseTime,
            arms.semantic.size,
            arms.semanticTime,
            documentHits.size,
            started.elapsedNow(),
        )
        return clientHits + documentHits
    }

    /**
     * The three floored document rankings for one query: what fusion combines, and what
     * `SearchArmAblationTest` measures one arm at a time. [expandSparse] exists for that test only —
     * production runs the sparse arm on the bare query. Measured, the probes changed no golden rank
     * but lengthened pages: a two-word probe like "bank statement" is a strong partial match for
     * most of the corpus, so "address proof" carried fifteen sparse candidates into fusion instead
     * of three.
     */
    internal fun documentArms(
        query: String,
        expandSparse: Boolean = false,
    ): DocumentArms {
        val probes = expander.expand(query)
        val (keyword, keywordTime) = measureTimedValue { findLexically(query) }
        val (sparse, sparseTime) = measureTimedValue { findSparsely(if (expandSparse) probes else listOf(query)) }
        val (semantic, semanticTime) = measureTimedValue { findSemantically(probes) }
        return DocumentArms(keyword, sparse, semantic, keywordTime, sparseTime, semanticTime)
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
     * The sparse arm: the query's IDF-weighted wordpieces against the learned, expanded chunks. Written
     * over a list of probes so the ablation can run the lexicon through it, but production passes one.
     */
    private fun findSparsely(probes: List<String>): List<DocumentMatch> {
        val best =
            embeddings
                .encodeQueriesSparsely(probes)
                .filter { !it.isEmpty() }
                .flatMap { vector ->
                    // Divided by the probe's own mass while the numbers are still inner products, so
                    // a two-word and a six-word probe meet the absolute floor on one scale: the score
                    // becomes the average learned weight the best chunk gives the probe's terms.
                    documents.sparseSearch(vector, properties.candidateDocuments).map { it.copy(score = it.score / vector.mass) }
                }.bestByDocument()

        val ceiling = best.firstOrNull()?.score ?: return emptyList()
        val floor = maxOf(properties.sparseFloor, ceiling * properties.sparseFloorRatio)
        return best.filter { it.score >= floor }
    }

    /**
     * Runs the semantic arm once per probe and keeps each document's best result across all of them.
     * The maximum, not a blend: averaging "address proof" with "utility bill" gives a vector that
     * matches both worse than either does alone.
     */
    private fun findSemantically(probes: List<String>): List<DocumentMatch> {
        // All probes are embedded in one padded batch — one forward pass, not one per probe.
        val best =
            embeddings
                .embedQueries(probes)
                .flatMap { vector -> documents.semanticSearch(vector, properties.candidateDocuments) }
                .bestByDocument()

        // Both floors apply here, while the numbers are still cosine similarities: after fusion
        // there are only ranks, and "not similar enough" cannot be expressed as a rank.
        val ceiling = best.firstOrNull()?.score ?: 0.0
        val floor = maxOf(properties.semanticFloor, ceiling * properties.semanticFloorRatio)
        return best.filter { it.score >= floor }
    }

    private fun fuse(
        arms: DocumentArms,
        limit: Int,
    ): List<DocumentHit> {
        val (keyword, sparse, semantic) = arms
        // Later entries win, so a document's reference and fallback snippet come from the most
        // literal arm that found it.
        val byId = (semantic + sparse + keyword).associateBy { it.reference.id }
        val fused =
            ReciprocalRankFusion.fuse(
                listOf(
                    RankedList(KEYWORD, keyword.map { it.reference.id }),
                    RankedList(SPARSE, sparse.map { it.reference.id }),
                    RankedList(SEMANTIC, semantic.map { it.reference.id }),
                ),
                properties.rrfK,
            )

        // Exact ties are common: two documents that placed equally in different lists score
        // identically. Agreement breaks them first, then the most literal evidence — a lexical hit
        // is a fact, the token is in the document; a sparse hit is the token or a learned expansion
        // of it in a chunk; a semantic hit is an estimate — then title, and only then id, which is
        // there to make the order stable rather than to mean anything. Deployments do not share
        // ids, so anything that reordered on id would rank differently from one install to the
        // next. Sorting uses the full-precision score; rounding is presentation and would
        // otherwise manufacture extra ties.
        val headlines = keyword.associate { it.reference.id to it.snippet }
        return fused
            .map { item -> Candidate(item, byId.getValue(item.id), EVIDENCE_ORDER.filter { it in item.sources }) }
            .sortedWith(
                compareByDescending<Candidate> { it.fused.score }
                    .thenByDescending { it.sources.size }
                    .thenBy { evidenceRank(it.sources.first()) }
                    .thenBy { it.match.reference.title }
                    .thenBy { it.match.reference.id },
            ).take(limit)
            .map { (item, match, sources) ->
                DocumentHit(
                    score = item.score.asScore(),
                    matchedOn = if (sources.size > 1) MULTIPLE else sources.single(),
                    sources = sources,
                    // A keyword hit carries a ts_headline built around the query terms, which reads
                    // better than a whole chunk; any other hit falls back to its best passage — for
                    // a sparse hit, the chunk whose learned terms the query's own weighed most.
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

/** A fused document on its way to becoming a hit: the fusion result, the arm supplying its text, and who found it. */
private data class Candidate(
    val fused: FusedDocument,
    val match: DocumentMatch,
    val sources: List<String>,
)
