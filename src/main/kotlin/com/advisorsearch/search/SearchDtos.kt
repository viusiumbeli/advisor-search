package com.advisorsearch.search

import com.advisorsearch.clients.Client
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Scores are reported to four decimals. The extra digits of a double are not meaningful here — a
 * reciprocal rank fusion weight of 0.016393442622950820 says nothing more than 0.0164 does — and
 * they make a response hard to read.
 */
internal fun Double.asScore(): Double = BigDecimal(this).setScale(4, RoundingMode.HALF_UP).toDouble()

/**
 * One flat array, as the task's OpenAPI fragment specifies, but ordered in two blocks: every client
 * hit, then every document hit.
 *
 * The blocks are deliberately not interleaved. A client score is a similarity in 0..1 and a
 * document score is a reciprocal-rank-fusion weight around 0.016; sorting them into one sequence
 * would be comparing two different measurements. Scores are comparable within a block only.
 */
sealed interface SearchHit {
    val type: String
    val score: Double
    val matchedOn: String
}

@JsonPropertyOrder("type", "score", "matched_on", "client")
data class ClientHit(
    override val score: Double,
    override val matchedOn: String,
    val client: Client,
) : SearchHit {
    override val type: String = "client"
}

@JsonPropertyOrder("type", "score", "matched_on", "snippet", "document")
data class DocumentHit(
    override val score: Double,
    override val matchedOn: String,
    val snippet: String,
    val document: DocumentReference,
) : SearchHit {
    override val type: String = "document"
}

/**
 * A document as it appears in results: everything except `content`. Search returns snippets, and a
 * page of hits should not carry several 8000-word documents; the full text is one GET away.
 */
data class DocumentReference(
    val id: UUID,
    val clientId: UUID,
    val title: String,
    val createdAt: OffsetDateTime,
)
