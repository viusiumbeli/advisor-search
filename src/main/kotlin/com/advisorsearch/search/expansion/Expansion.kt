package com.advisorsearch.search.expansion

/**
 * One query prepared for the semantic arm: its own vector first, then the lexicon's expansions, and
 * the concepts that brought them. Plain classes rather than data classes on purpose — `FloatArray`
 * equality is identity, and nothing compares these.
 */
class Expansion(
    val query: String,
    /** The user's own words first, at most `MAX_PROBES` in all. */
    val probes: List<Probe>,
    /** Empty for most queries; strongest first. What the search log prints. */
    val concepts: List<ConceptMatch>,
) {
    val texts: List<String> = probes.map(Probe::text)

    val vectors: List<FloatArray> = probes.map(Probe::vector)
}

/** A probe text with the unit vector the semantic arm scans with; an expansion's was embedded at startup. */
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
