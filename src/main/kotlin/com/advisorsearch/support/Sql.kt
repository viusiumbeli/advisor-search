package com.advisorsearch.support

import com.advisorsearch.embedding.SPARSE_DIMENSIONS
import com.advisorsearch.embedding.SparseVector

/**
 * pgvector's input format. There is no implicit cast from a JDBC string to `vector`, so every
 * statement that binds one of these also has to say `CAST(? AS vector)`.
 */
fun FloatArray.toVectorLiteral(): String = joinToString(prefix = "[", postfix = "]", separator = ",")

/**
 * Escapes a user query for use inside `LIKE '%' || ? || '%' ESCAPE '\'`.
 *
 * Without this, a query containing `%` or `_` would be a wildcard rather than the literal text the
 * user typed, and `%` alone would match every client in the table.
 */
fun String.escapeLikeWildcards(): String =
    buildString(length) {
        for (character in this@escapeLikeWildcards) {
            if (character == '\\' || character == '%' || character == '_') append('\\')
            append(character)
        }
    }

/**
 * pgvector's sparsevec input format: `{index:value,...}/dimensions`, with indices numbered from 1
 * like SQL arrays, so vocabulary id 0 renders as `1:`. pgvector sorts the entries itself and rejects
 * duplicates; a [SparseVector] already guarantees both, and emitting them in order matches how the
 * value is stored. A zero weight is never emitted — the parser counts entries against a 16,000 cap
 * before it drops zeros — and the vector's own invariant rules them out. As with `vector`, every
 * statement that binds one of these has to say `CAST(? AS sparsevec)`.
 */
fun SparseVector.toSparseVectorLiteral(): String {
    val ids = indices
    val values = weights
    return buildString {
        append('{')
        for (position in ids.indices) {
            if (position > 0) append(',')
            append(ids[position] + 1).append(':').append(values[position])
        }
        append("}/").append(SPARSE_DIMENSIONS)
    }
}
