package com.advisorsearch.documents

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@Tag(name = "Documents")
class DocumentController(
    private val service: DocumentService,
) {
    @PostMapping("/clients/{clientId}/documents")
    @Operation(
        summary = "Add a document to a client",
        description =
            "Chunks and embeds the content before responding, so the document is searchable as " +
                "soon as the 201 is returned. Content is capped at 100000 characters.",
    )
    fun create(
        @PathVariable clientId: UUID,
        @Valid @RequestBody request: CreateDocumentRequest,
    ): ResponseEntity<Document> {
        val document = service.create(clientId, request)
        // Documents carry globally unique ids, so their canonical location is /documents/{id};
        // the client path is where a document is created, not where it lives.
        return ResponseEntity.created(URI.create("/documents/${document.id}")).body(document)
    }

    @GetMapping("/documents/{id}")
    @Operation(summary = "Fetch a document, including its full content")
    fun get(
        @PathVariable id: UUID,
    ): Document = service.get(id)

    @GetMapping("/documents/{id}/summary")
    @Operation(
        summary = "Extractive summary of a document",
        description =
            "Returns the passages closest to the document's own embedding centroid, in reading " +
                "order. Every sentence is verbatim from the document.",
    )
    fun summary(
        @PathVariable id: UUID,
    ): DocumentSummary = service.summarise(id)
}
