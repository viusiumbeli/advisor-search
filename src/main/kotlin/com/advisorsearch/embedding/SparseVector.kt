package com.advisorsearch.embedding

import kotlin.math.ln1p

/** bert-base-uncased WordPiece vocabulary size; also the `sparsevec(30522)` column width in the schema. */
const val SPARSE_DIMENSIONS = 30522

/**
 * A SPLADE representation: the vocabulary ids a text activates — ascending, unique, 0-based — each
 * with a strictly positive weight. The sparse analogue of the FloatArray the dense encoder returns.
 * Two of them score by plain inner product, deliberately unnormalised: that is what the models were
 * trained against, and it is why the weights are not scaled to unit length the way the dense
 * vectors are.
 */
class SparseVector(
    val indices: IntArray,
    val weights: FloatArray,
) {
    init {
        require(indices.size == weights.size) { "Each term needs exactly one weight" }
        for (position in indices.indices) {
            require(weights[position] > 0f) { "Sparse weights must be strictly positive" }
            require(position == 0 || indices[position] > indices[position - 1]) { "Sparse indices must be ascending and unique" }
        }
    }

    val termCount: Int get() = indices.size

    /**
     * The sum of the weights. For a query this is its own mass: dividing an inner product by it puts
     * a one-word and a six-word query on one scale, which is what lets an absolute floor exist.
     */
    val mass: Double
        get() {
            var sum = 0.0
            for (weight in weights) sum += weight
            return sum
        }

    fun isEmpty(): Boolean = indices.isEmpty()

    /** The inner product over the terms both vectors carry — the same number `-(a <#> b)` gives in Postgres. */
    fun dot(other: SparseVector): Double {
        var sum = 0.0
        var mine = 0
        var theirs = 0
        while (mine < indices.size && theirs < other.indices.size) {
            val a = indices[mine]
            val b = other.indices[theirs]
            when {
                a == b -> {
                    sum += weights[mine].toDouble() * other.weights[theirs]
                    mine++
                    theirs++
                }
                a < b -> mine++
                else -> theirs++
            }
        }
        return sum
    }

    companion object {
        val EMPTY = SparseVector(IntArray(0), FloatArray(0))
    }
}

/**
 * The sparse form of a pooled vocabulary-width array: every positive entry becomes a term weighted
 * `log(1 + value)`, and when more than [maxTerms] are positive only the heaviest survive. Ids stay
 * ascending either way, which is the order pgvector stores them in.
 */
internal fun sparseVectorOf(
    pooled: FloatArray,
    maxTerms: Int,
): SparseVector {
    var count = 0
    for (value in pooled) if (value > 0f) count++
    if (count == 0) return SparseVector.EMPTY

    val ids = IntArray(count)
    val weights = FloatArray(count)
    var next = 0
    for (id in pooled.indices) {
        if (pooled[id] > 0f) {
            ids[next] = id
            weights[next] = ln1p(pooled[id])
            next++
        }
    }
    if (count <= maxTerms) return SparseVector(ids, weights)

    val kept =
        weights.indices
            .sortedByDescending { weights[it] }
            .take(maxTerms)
            .sorted()
    return SparseVector(IntArray(maxTerms) { ids[kept[it]] }, FloatArray(maxTerms) { weights[kept[it]] })
}
