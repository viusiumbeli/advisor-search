package com.advisorsearch.documents

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

/** Response shape from the task's OpenAPI fragment; serialised as snake_case. */
data class Document(
    val id: UUID,
    val clientId: UUID,
    val title: String,
    val content: String,
    val createdAt: OffsetDateTime,
)

data class CreateDocumentRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 500, message = "must be at most 500 characters")
    val title: String?,
    @field:NotBlank(message = "must not be blank")
    val content: String?,
)

/**
 * Extractive summary: the chunks nearest the document's own centroid, returned in reading order.
 *
 * It is a selection, not a generation, so every sentence is verbatim from the document and no
 * second model is involved.
 */
data class DocumentSummary(
    val documentId: UUID,
    val title: String,
    val method: String,
    val chunkCount: Int,
    val passages: List<Passage>,
) {
    data class Passage(
        val chunkIndex: Int,
        val text: String,
        val centrality: Double,
    )
}
