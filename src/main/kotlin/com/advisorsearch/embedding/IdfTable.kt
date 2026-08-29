package com.advisorsearch.embedding

import tools.jackson.databind.ObjectMapper
import java.nio.file.Path

/**
 * The query side of an inference-free sparse model: one frozen weight per vocabulary id, read from
 * the checkpoint's `idf.json` (keyed by token string) through the checkpoint's own `tokenizer.json`
 * (token to id). A query is then its distinct wordpieces, each weighted from here, so searching runs
 * no second forward pass.
 *
 * The tokenizer that produces those wordpieces at run time is the dense model's. That is legitimate
 * only because the two vocabularies are identical entry for entry at the pinned revisions, so [load]
 * checks it: a revision bump that changed either file fails the instance rather than feeding one
 * model's ids to the other's table.
 */
class IdfTable(
    private val weights: FloatArray,
    /** [PAD], [UNK], [CLS], [SEP] and [MASK]: zeroed on the document side, so they can never score. */
    val specialTokenIds: IntArray,
) {
    val size: Int get() = weights.size

    operator fun get(tokenId: Int): Float = weights[tokenId]

    /**
     * The model card's query: `query_vector[input_ids] = 1` then `* idf` — presence, not counts, so a
     * repeated word weighs once. Specials are dropped because the document side zeroes them; keeping
     * them would add a constant to every score and nothing to any ranking.
     */
    fun weigh(tokenIds: LongArray): SparseVector {
        val ids =
            tokenIds
                .map(Long::toInt)
                .filter { it !in specialTokenIds && weights[it] > 0f }
                .distinct()
                .sorted()
        if (ids.isEmpty()) return SparseVector.EMPTY
        return SparseVector(ids.toIntArray(), FloatArray(ids.size) { weights[ids[it]] })
    }

    companion object {
        fun load(
            idfPath: Path,
            sparseTokenizerPath: Path,
            denseTokenizerPath: Path,
            objectMapper: ObjectMapper,
        ): IdfTable {
            val sparseTokenizer = objectMapper.readTree(sparseTokenizerPath)
            val vocabulary = sparseTokenizer.path("model").path("vocab")
            check(vocabulary == objectMapper.readTree(denseTokenizerPath).path("model").path("vocab")) {
                "$sparseTokenizerPath and $denseTokenizerPath do not share a vocabulary; one tokenizer cannot serve both models"
            }
            check(vocabulary.size() == SPARSE_DIMENSIONS) { "Expected a $SPARSE_DIMENSIONS-term vocabulary, found ${vocabulary.size()}" }

            val idf = objectMapper.readTree(idfPath)
            check(idf.size() == vocabulary.size()) { "$idfPath has ${idf.size()} weights for a ${vocabulary.size()}-term vocabulary" }
            val weights = FloatArray(SPARSE_DIMENSIONS)
            for ((token, id) in vocabulary.properties()) {
                val weight = idf.get(token) ?: error("$idfPath has no weight for the token '$token'")
                weights[id.asInt()] = weight.doubleValue().toFloat()
            }
            val specials =
                sparseTokenizer
                    .path("added_tokens")
                    .values()
                    .filter { it.path("special").booleanValue(false) }
                    .map { it.path("id").asInt() }
                    .sorted()
                    .toIntArray()
            return IdfTable(weights, specials)
        }
    }
}
