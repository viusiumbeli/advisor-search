package com.advisorsearch.support

/** A run of any whitespace, shared by everything that collapses text to single spaces. */
internal val WHITESPACE_RUN = Regex("\\s+")

/**
 * The value as it will be stored, rejected when trimming leaves nothing behind.
 *
 * Bean Validation measures blankness with `String.trim()`, which strips only characters up to
 * U+0020, while every field it guards here is stored Kotlin-trimmed, which also strips U+00A0 and
 * the rest of Unicode's spaces. A field holding one non-breaking space therefore satisfies
 * `@NotBlank`, reaches the insert empty and trips a not-blank CHECK — a 500 for what is plainly a
 * bad request. [field] is the wire name, so the message names what the caller actually sent.
 */
internal fun String.trimmedOrReject(field: String): String = trim().ifEmpty { throw InvalidRequestException("$field must not be blank") }

/**
 * [end] moved back one place when it would fall between the halves of a surrogate pair, so
 * `substring(0, end)` can never produce half a character. Astral-plane text — emoji, the rarer CJK
 * blocks — is what reaches this.
 */
internal fun String.wholeCharacterEnd(end: Int): Int =
    if (end in 1..<length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end - 1 else end
