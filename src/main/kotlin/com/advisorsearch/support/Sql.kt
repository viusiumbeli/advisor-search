package com.advisorsearch.support

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
