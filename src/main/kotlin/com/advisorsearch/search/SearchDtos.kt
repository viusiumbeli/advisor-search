package com.advisorsearch.search

import com.advisorsearch.clients.Client
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Four decimals: the remaining digits of a double are not meaningful here — a fusion weight of
 * 0.016393442622950820 says nothing more than 0.0164 — and they make a response hard to read.
 */
internal fun Double.asScore(): Double = toBigDecimal().setScale(4, RoundingMode.HALF_UP).toDouble()

/**
 * One flat array, as the task's OpenAPI fragment specifies, ordered in two blocks: client hits, then
 * document hits. Never interleaved — a client score is a similarity in 0..1 and a document score a
 * fusion weight around 0.016, so scores compare within a block only.
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

/**
 * `matched_on` names the one retriever that found the document, or `multiple`; `sources` always
 * lists them, most literal first, so a caller can see which arms agreed without parsing a label.
 */
@JsonPropertyOrder("type", "score", "matched_on", "sources", "snippet", "document")
data class DocumentHit(
    override val score: Double,
    override val matchedOn: String,
    val sources: List<String>,
    val snippet: String,
    val document: DocumentReference,
) : SearchHit {
    override val type: String = "document"
}

/**
 * A document as it appears in results: everything except `content`. Search returns snippets, so a
 * page of hits stays small; the full text is one GET away.
 */
data class DocumentReference(
    val id: UUID,
    val clientId: UUID,
    val title: String,
    val createdAt: OffsetDateTime,
)
