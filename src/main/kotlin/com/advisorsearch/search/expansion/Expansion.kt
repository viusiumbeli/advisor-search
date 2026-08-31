package com.advisorsearch.search.expansion

/**
 * One query prepared for the retrievers: its own vector first, then the lexicon's document types as
 * further semantic probes, the matched rules' document phrases for the arms that match text, and the
 * concepts that brought both. Plain classes rather than data classes on purpose — `FloatArray`
 * equality is identity, and nothing compares these.
 */
class Expansion(
    val query: String,
    /** The user's own words first, at most `MAX_PROBES` in all. */
    val probes: List<Probe>,
    /**
     * The matched rules' document phrases, interleaved and uncapped: the lexical arm takes them all
     * in one query, and the sparse arm takes as many as it can afford. Empty for most queries.
     */
    val phrases: List<String>,
    /** Empty for most queries; strongest first. What the search log prints. */
    val concepts: List<ConceptMatch>,
) {
    val texts: List<String> = probes.map(Probe::text)

    val vectors: List<FloatArray> = probes.map(Probe::vector)
}

/** A probe text with the unit vector the semantic arm scans with; a document type's was embedded at startup. */
class Probe(
    val text: String,
    val vector: FloatArray,
)

/** Why a rule fired: the concept, the phrasing the query came nearest to, and the cosine between them. */
data class ConceptMatch(
    val concept: String,
    val phrasing: String,
    val similarity: Double,
)
