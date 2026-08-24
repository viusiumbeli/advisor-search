package com.advisorsearch.seed

import com.advisorsearch.clients.ClientService
import com.advisorsearch.clients.CreateClientRequest
import com.advisorsearch.documents.CreateDocumentRequest
import com.advisorsearch.documents.DocumentRepository
import com.advisorsearch.documents.DocumentService
import com.advisorsearch.seed.corpus.Corpus
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger(SeedService::class.java)
private const val CORPUS = "seed/corpus.json"
private const val DOCUMENTS = "seed/documents"

/**
 * Loads the demo corpus through the ordinary service layer rather than inserting rows, so seeded
 * documents are chunked and embedded by exactly the code path a real POST uses. Idempotent by client
 * email and document title, so restarting a container with a volume does not duplicate the corpus.
 */
@Service
class SeedService(
    private val clients: ClientService,
    private val documents: DocumentRepository,
    private val documentService: DocumentService,
    private val objectMapper: ObjectMapper,
) {
    fun seed(): SeedSummary {
        val corpus: Corpus = objectMapper.readValue(ClassPathResource(CORPUS).inputStream)
        val started = TimeSource.Monotonic.markNow()
        var clientsCreated = 0
        var documentsCreated = 0
        var skipped = 0

        for (seedClient in corpus.clients) {
            val client =
                clients.findByEmail(seedClient.email)
                    ?: clients
                        .create(
                            CreateClientRequest(
                                firstName = seedClient.firstName,
                                lastName = seedClient.lastName,
                                email = seedClient.email,
                                description = seedClient.description,
                                socialLinks = seedClient.socialLinks,
                            ),
                        ).also { clientsCreated++ }

            for (seedDocument in seedClient.documents) {
                if (documents.existsForClientWithTitle(client.id, seedDocument.title)) {
                    skipped++
                    continue
                }
                val content = ClassPathResource("$DOCUMENTS/${seedDocument.file}").getContentAsString(UTF_8)
                documentService.create(
                    client.id,
                    CreateDocumentRequest(title = seedDocument.title, content = content),
                )
                documentsCreated++
            }
        }

        log.info(
            "Seed complete in {}: {} clients and {} documents created, {} documents already present",
            started.elapsedNow(),
            clientsCreated,
            documentsCreated,
            skipped,
        )
        return SeedSummary(clientsCreated, documentsCreated, skipped)
    }
}
