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

/** Wire shape; required fields nullable for the reason given on [com.advisorsearch.clients.CreateClientRequest]. */
data class CreateDocumentRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 500, message = "must be at most 500 characters")
    val title: String?,
    @field:NotBlank(message = "must not be blank")
    val content: String?,
)

data class NewDocument(
    val title: String,
    val content: String,
)

fun CreateDocumentRequest.toNewDocument(): NewDocument = NewDocument(title = title!!.trim(), content = content!!.trim())

/** Extractive summary: the chunks nearest the document's own centroid, in reading order. */
data class DocumentSummary(
    val documentId: UUID,
    val title: String,
    val method: String,
    val chunkCount: Int,
    val passages: List<SummaryPassage>,
)

data class SummaryPassage(
    val chunkIndex: Int,
    val text: String,
    val centrality: Double,
)
