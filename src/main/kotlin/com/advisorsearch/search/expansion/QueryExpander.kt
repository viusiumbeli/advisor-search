package com.advisorsearch.search.expansion

import com.advisorsearch.config.SearchProperties
import com.advisorsearch.embedding.EmbeddingService
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import kotlin.time.measureTimedValue

private val log = LoggerFactory.getLogger(QueryExpander::class.java)
private const val LEXICON = "search/query-expansions.json"

/** Each probe is an extra vector scan; the ceiling keeps that bounded. */
private const val MAX_PROBES = 5

/**
 * Supplies what a general-purpose model does not know: which documents serve which purpose in this
 * domain. "Address proof finds a utility bill" is procedural knowledge, not similarity, so it lives
 * in a JSON lexicon a domain expert can edit without touching Kotlin. The lexicon says *what* to
 * search for; a model decides *when* it applies. Each rule's phrasings are embedded once at startup
 * with the model the semantic arm searches with, and a query fires a rule when its own vector comes
 * within a measured distance of one of them — so "documents that show where the client lives" reaches
 * the address rule without containing any listed phrase, which the substring matcher this replaced
 * never could. The floors that bound the model's say are calibrated in `ConceptFloorTest` and written
 * up in docs/search-design.md, "Matching a query to a concept".
 *
 * Expansion widens the semantic arm only: the lexical arm's value is precision, and the sparse arm was
 * measured with the probes and without — same ranks, longer pages.
 */
@Component
class QueryExpander(
    objectMapper: ObjectMapper,
    private val embeddings: EmbeddingService,
    private val properties: SearchProperties,
) {
    private val rules: List<EmbeddedRule>

    init {
        val lexicon = objectMapper.readValue(ClassPathResource(LEXICON).inputStream, ExpansionLexicon::class.java).rules
        require(lexicon.isNotEmpty()) { "$LEXICON holds no rules" }
        // One batch of short phrases per rule, once at construction: EMBED_BATCH's reasoning about
        // sequence-squared native memory does not bind on ~8-token texts.
        val (embedded, elapsed) =
            measureTimedValue {
                lexicon.map { rule ->
                    EmbeddedRule(rule.concept, probesOf((listOf(rule.concept) + rule.paraphrases).distinct()), probesOf(rule.expansions))
                }
            }
        rules = embedded
        log.info(
            "Loaded {} query expansion rules; embedded {} phrasings and {} expansions in {}",
            rules.size,
            rules.sumOf { it.phrasings.size },
            rules.sumOf { it.expansions.size },
            elapsed,
        )
    }

    /**
     * The query embedded once, the concepts it comes near enough to, and the probes that follow: the
     * user's own words first, then the expansions of every matched concept, interleaved. A query about
     * nothing in the lexicon costs one forward pass — which the semantic arm needed anyway — and a few
     * dozen dot products.
     */
    fun expand(query: String): Expansion {
        val vector = embeddings.embedQuery(query)
        val scored = similarities(vector)
        // Absolute and relative, like the semantic arm's own floors: the absolute floor rejects a query
        // about nothing in the lexicon, the relative one stops a single-concept query dragging its
        // embedding-space sibling in — the strongest rule always passes it, a genuine two-concept
        // query keeps both, and only weaker also-rans are pruned.
        val floor = maxOf(properties.conceptFloor, scored.first().similarity * properties.conceptFloorRatio)
        val matched = scored.filter { it.similarity >= floor }
        val expansions = interleave(matched.map { match -> rules.first { it.concept == match.concept }.expansions })
        val probes = (listOf(Probe(query, vector)) + expansions).distinctBy(Probe::text).take(MAX_PROBES)
        if (matched.isNotEmpty()) log.debug("Expanded '{}' via {} into {} probes", query, matched, probes.size)
        return Expansion(query, probes, matched)
    }

    /** Every rule's nearest phrasing to [query], unfloored, strongest first — what [expand] cuts and `ConceptFloorTest` tabulates. */
    internal fun similarities(query: String): List<ConceptMatch> = similarities(embeddings.embedQuery(query))

    private fun similarities(vector: FloatArray): List<ConceptMatch> =
        rules
            .map { rule ->
                rule.phrasings.map { ConceptMatch(rule.concept, it.text, similarity(vector, it.vector)) }.maxBy { it.similarity }
            }.sortedByDescending { it.similarity }

    private fun probesOf(texts: List<String>): List<Probe> = texts.zip(embeddings.embedQueries(texts), ::Probe)

    /**
     * One expansion from every matched concept before a second from any of them, so the ceiling
     * shortens each concept rather than silencing whole ones. Taken in lexicon order instead, a
     * query naming two concepts — "proof of address and proof of identity" — spends the entire
     * budget on whichever rule is listed first; the second concept's documents are then left
     * scoring against the bare query, and the relative floor drops them out of the results with
     * nothing to say they were ever considered. Now that paraphrases reach rules too, more queries
     * meet the ceiling, and what it guarantees is one expansion per concept rather than several.
     */
    private fun interleave(expansions: List<List<Probe>>): List<Probe> {
        if (expansions.isEmpty()) return emptyList()
        val deepest = expansions.maxOf { it.size }
        return (0..<deepest).flatMap { position -> expansions.mapNotNull { it.getOrNull(position) } }
    }
}

/** A rule with its phrasings and expansions embedded: working state, like `Candidate` in SearchService.kt. */
private class EmbeddedRule(
    val concept: String,
    val phrasings: List<Probe>,
    val expansions: List<Probe>,
)

/** Both vectors are unit length — OnnxEmbedder normalises — so the dot product is the cosine, the number `1 - (a <=> b)` gives in Postgres. */
private fun similarity(
    a: FloatArray,
    b: FloatArray,
): Double {
    var dot = 0.0
    for (index in a.indices) dot += a[index].toDouble() * b[index]
    return dot
}
