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
 * A rule carries two lists and each goes to the arm that can use it. Its document types are embedded
 * and widen the semantic arm, as they always have. Its document phrases are text the documents
 * themselves carry, so they widen the arms that match tokens — one more full-text query and a few
 * sparse probes — and they are never embedded, which is why they cost no part of the probe ceiling.
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
                    require(rule.documentPhrases.none(String::isBlank)) { "${rule.concept} has a blank document phrase" }
                    EmbeddedRule(
                        rule.concept,
                        probesOf((listOf(rule.concept) + rule.paraphrases).distinct()),
                        probesOf(rule.documentTypes),
                        rule.documentPhrases,
                    )
                }
            }
        rules = embedded
        log.info(
            "Loaded {} query expansion rules; embedded {} phrasings and {} document types in {}, and read {} document phrases",
            rules.size,
            rules.sumOf { it.phrasings.size },
            rules.sumOf { it.documentTypes.size },
            elapsed,
            rules.sumOf { it.phrases.size },
        )
    }

    /**
     * The query embedded once, the concepts it comes near enough to, and what each matched rule adds:
     * probes for the semantic arm — the user's own words first, then the matched concepts' document
     * types, interleaved — and the same rules' document phrases for the arms that match text. A query
     * about nothing in the lexicon costs one forward pass, which the semantic arm needed anyway, and a
     * few dozen dot products; it adds no phrases, so it issues no extra query either.
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
        val matchedRules = matched.map { match -> rules.first { it.concept == match.concept } }
        val types = interleave(matchedRules.map { it.documentTypes })
        val probes = (listOf(Probe(query, vector)) + types).distinctBy(Probe::text).take(MAX_PROBES)
        // Uncapped, unlike the probes: the lexical arm spends one scan however many phrases it is
        // given, and the arm that does pay per phrase applies its own ceiling.
        val phrases = interleave(matchedRules.map { it.phrases }).distinct()
        if (matched.isNotEmpty()) {
            log.debug("Expanded '{}' via {} into {} probes and phrases {}", query, matched, probes.size, phrases)
        }
        return Expansion(query, probes, phrases, matched)
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
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        if (lists.isEmpty()) return emptyList()
        val deepest = lists.maxOf { it.size }
        return (0..<deepest).flatMap { position -> lists.mapNotNull { it.getOrNull(position) } }
    }
}

/** A rule with its phrasings and document types embedded: working state, like `Candidate` in SearchService.kt. */
private class EmbeddedRule(
    val concept: String,
    val phrasings: List<Probe>,
    val documentTypes: List<Probe>,
    /** Text, not vectors: these never meet the dense model. */
    val phrases: List<String>,
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
