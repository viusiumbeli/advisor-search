package com.advisorsearch.documents

import com.advisorsearch.clients.ClientRepository
import com.advisorsearch.config.IngestProperties
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.support.InvalidRequestException
import com.advisorsearch.support.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.time.measureTimedValue

private val log = LoggerFactory.getLogger(DocumentService::class.java)
private const val SUMMARY_PASSAGES = 3

@Service
class DocumentService(
    private val documents: DocumentRepository,
    private val clients: ClientRepository,
    private val embeddings: EmbeddingService,
    private val properties: IngestProperties,
) {
    /**
     * Ingest is synchronous: the 201 means the document is chunked, embedded and searchable, with no
     * window in which a client can read back a document that search cannot find. That holds while
     * ingest stays in the low seconds; the README records the p99 at which this should become a
     * background job instead.
     */
    fun create(
        clientId: UUID,
        request: CreateDocumentRequest,
    ): Document {
        // Checked before any embedding: rejecting an unknown client should not cost model inference.
        if (!clients.exists(clientId)) throw ResourceNotFoundException("Client", clientId)

        val title = request.title!!.trim()
        val content = request.content!!.trim()
        if (content.length > properties.maxContentLength) {
            throw InvalidRequestException(
                "content is ${content.length} characters, which exceeds the " +
                    "${properties.maxContentLength} character limit for a single document",
            )
        }

        val chunks = embeddings.chunk(content)
        val (vectors, embedTime) = measureTimedValue { embeddings.embedChunks(title, chunks) }

        val document =
            documents.insertWithChunks(clientId, title, content, chunks, vectors, embeddings.modelId)
        log.info(
            "Ingested document {} for client {}: {} characters, {} chunks, embedded in {}",
            document.id,
            clientId,
            content.length,
            chunks.size,
            embedTime,
        )
        return document
    }

    fun get(id: UUID): Document = documents.findById(id) ?: throw ResourceNotFoundException("Document", id)

    fun summarise(id: UUID): DocumentSummary {
        val document = get(id)
        val passages =
            documents
                .centralChunks(id, SUMMARY_PASSAGES)
                .sortedBy { it.chunkIndex }
        return DocumentSummary(
            documentId = document.id,
            title = document.title,
            method = "extractive: chunks nearest the document centroid, in reading order",
            chunkCount = documents.countChunks(id),
            passages = passages,
        )
    }

}
