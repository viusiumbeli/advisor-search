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
private const val PHRASE = "phrase"
private const val SPARSE = "sparse"
private const val SEMANTIC = "semantic"

/**
 * The sparse arm pays a full scan per probe — 1-2 ms on the seeded corpus but ~420 ms at 99,700
 * chunks, where its column is detoasted on the way past — so it gets the same five-probe ceiling the
 * semantic arm has: the query and at most four of the lexicon's phrases. The lexical arm needs no
 * such ceiling; one tsquery costs one scan however many alternatives it holds.
 */
private const val MAX_SPARSE_PHRASE_PROBES = 4

/** What a document hit says when more than one retriever found it; `sources` then says which. */
private const val MULTIPLE = "multiple"

/**
 * Most to least literal, used to order `sources` and to break exact fusion ties. A keyword hit is the
 * token in the document, and the token is one the user typed; a phrase hit is also a token in the
 * document, adjacency-checked by the same grammar, but the lexicon supplied it rather than the user,
 * which is the one step that separates them; a sparse hit is the token, or a term the model learned
 * the chunk implies, in a chunk's representation; a semantic hit is an estimate of meaning.
 */
private val EVIDENCE_ORDER = listOf(KEYWORD, PHRASE, SPARSE, SEMANTIC)

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

        // Each arm's own time beside its count, so the cost of a retriever is a grep away — and the
        // concepts the query reached, so an expansion is too.
        log.info(
            "search q='{}' clients={} concepts={} ({}) keyword={} ({}) phrase={} ({}) sparse={} ({}) semantic={} ({}) returned={} in {}",
            query,
            clientHits.size,
            arms.concepts.joinToString(prefix = "[", postfix = "]") { "${it.concept} ${it.similarity.asScore()} via '${it.phrasing}'" },
            arms.expansionTime,
            arms.keyword.size,
            arms.keywordTime,
            arms.phrase.size,
            arms.phraseTime,
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
     * The four floored document rankings for one query: what fusion combines, and what
     * `SearchArmAblationTest` measures one arm at a time. The expander embeds the query exactly once
     * and hands the vector on, so the semantic arm runs no inference of its own.
     *
     * Both flags exist for that test. [sparsePhrases] is on in production and turns the lexicon's
     * phrases into sparse probes; [expandSparse] adds the document *type* names, which stays off,
     * because measured they changed no golden rank and only lengthened pages — a two-word type name
     * like "bank statement" is a strong partial match for most of the corpus, so "address proof"
     * carried fifteen sparse candidates into fusion instead of three.
     */
    internal fun documentArms(
        query: String,
        expandSparse: Boolean = false,
        sparsePhrases: Boolean = true,
    ): DocumentArms {
        val (expansion, expansionTime) = measureTimedValue { expander.expand(query) }
        val (keyword, keywordTime) = measureTimedValue { findLexically(query) }
        val (phrase, phraseTime) = measureTimedValue { findByPhrase(expansion.phrases, keyword) }
        val sparseProbes =
            listOf(query) +
                (if (sparsePhrases) expansion.phrases.take(MAX_SPARSE_PHRASE_PROBES) else emptyList()) +
                (if (expandSparse) expansion.texts.drop(1) else emptyList())
        val (sparse, sparseTime) = measureTimedValue { findSparsely(sparseProbes) }
        val (semantic, semanticTime) = measureTimedValue { findSemantically(expansion.vectors) }
        return DocumentArms(
            keyword,
            phrase,
            sparse,
            semantic,
            keywordTime,
            phraseTime,
            sparseTime,
            semanticTime,
            expansion.concepts,
            expansionTime,
        )
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
     * What the lexicon's phrases find that the user's own words did not. Its own tsquery, so its own
     * relative floor: `ts_rank_cd` has no scale that carries between queries, and a different tsquery
     * is a different query — measured on the seeded corpus, this page's best is 1.20 where the user's
     * own peaks at 0.83, so one shared floor would cut documents the user's words legitimately found.
     *
     * Documents the keyword arm already holds are dropped rather than ranked again: the same index
     * finding the same document twice is one fact, not two, and fusion counts lists. What is left is
     * exactly the documents the lexicon added, which is what `sources` then says.
     *
     * Re-sorted before it becomes a ranking. A phrase page ties constantly — a hit is typically one
     * phrase matched once, so its `ts_rank_cd` is the same number for every such document — and the
     * repository breaks ties by id, which is a fresh uuid on every seeded database. Title first keeps
     * a rank from depending on which install produced it.
     */
    private fun findByPhrase(
        phrases: List<String>,
        keyword: List<DocumentMatch>,
    ): List<DocumentMatch> {
        if (phrases.isEmpty()) return emptyList()
        val matches = documents.phraseSearch(phrases, properties.candidateDocuments)
        val best = matches.maxOfOrNull { it.score } ?: return emptyList()
        val alreadyFound = keyword.mapTo(mutableSetOf()) { it.reference.id }
        return matches
            .filter { it.score >= best * properties.keywordFloorRatio && it.reference.id !in alreadyFound }
            .sortedWith(
                compareByDescending<DocumentMatch> { it.score }
                    .thenBy { it.reference.title }
                    .thenBy { it.reference.id },
            )
    }

    /**
     * The sparse arm: the query's IDF-weighted wordpieces against the learned, expanded chunks. Written
     * over a list of probes so the ablation can run the lexicon through it; production passes the query
     * and the lexicon's first few phrases.
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
     * Runs the semantic arm once per probe vector and keeps each document's best result across all of
     * them. The maximum, not a blend: averaging "address proof" with "utility bill" gives a vector that
     * matches both worse than either does alone. The vectors arrive ready — the query's from its one
     * forward pass in the expander, the expansions' from startup — so no inference happens here.
     */
    private fun findSemantically(vectors: List<FloatArray>): List<DocumentMatch> {
        val best =
            vectors
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
        // Later entries win, so a document's reference and fallback snippet come from the most
        // literal arm that found it.
        val byId = (arms.semantic + arms.sparse + arms.phrase + arms.keyword).associateBy { it.reference.id }
        val fused =
            ReciprocalRankFusion.fuse(
                listOf(
                    RankedList(KEYWORD, arms.keyword.map { it.reference.id }),
                    RankedList(PHRASE, arms.phrase.map { it.reference.id }),
                    RankedList(SPARSE, arms.sparse.map { it.reference.id }),
                    RankedList(SEMANTIC, arms.semantic.map { it.reference.id }),
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
        // Both arms return a ts_headline built around what they matched; the user's own words explain
        // a result better than the lexicon's, so they are added last and win.
        val headlines = (arms.phrase + arms.keyword).associate { it.reference.id to it.snippet }
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
                    // A keyword or phrase hit carries a ts_headline built around the terms that
                    // matched, which reads better than a whole chunk; any other hit falls back to its
                    // best passage — for a sparse hit, the chunk whose learned terms the query's own
                    // weighed most.
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
