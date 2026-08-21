package com.advisorsearch.seed

import com.advisorsearch.clients.ClientRepository
import com.advisorsearch.clients.CreateClientRequest
import com.advisorsearch.documents.CreateDocumentRequest
import com.advisorsearch.documents.DocumentRepository
import com.advisorsearch.documents.DocumentService
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Loads the demo corpus so that a freshly started instance has something to search.
 *
 * It goes through the ordinary service layer rather than inserting rows directly, so the seeded
 * documents are chunked and embedded by exactly the code path a real POST uses. A fixture built
 * with a shortcut would be the wrong thing to demonstrate and the wrong thing to test against.
 *
 * Seeding is idempotent by client email and document title, so restarting a container with a
 * persistent volume does not duplicate the corpus.
 */
@Service
class SeedService(
    private val clients: ClientRepository,
    private val documents: DocumentRepository,
    private val documentService: DocumentService,
    private val objectMapper: ObjectMapper,
) {
    data class Corpus(
        val clients: List<SeedClient>,
    )

    data class SeedClient(
        val firstName: String,
        val lastName: String,
        val email: String,
        val description: String,
        val socialLinks: List<String> = emptyList(),
        val documents: List<SeedDocument> = emptyList(),
    )

    data class SeedDocument(
        val title: String,
        val file: String,
    )

    data class Result(
        val clientsCreated: Int,
        val documentsCreated: Int,
        val skipped: Int,
    )

    fun seed(): Result {
        val corpus = objectMapper.readValue(ClassPathResource(CORPUS).inputStream, Corpus::class.java)
        var clientsCreated = 0
        var documentsCreated = 0
        var skipped = 0
        val startedAt = System.nanoTime()

        for (seedClient in corpus.clients) {
            val existing = clients.findByEmail(seedClient.email)
            val client =
                existing ?: clients
                    .insert(
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
                val content = ClassPathResource("$DOCUMENTS/${seedDocument.file}").inputStream.reader().readText()
                documentService.create(
                    client.id,
                    CreateDocumentRequest(title = seedDocument.title, content = content),
                )
                documentsCreated++
            }
        }

        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        log.info(
            "Seed complete in {} ms: {} clients and {} documents created, {} documents already present",
            elapsed,
            clientsCreated,
            documentsCreated,
            skipped,
        )
        return Result(clientsCreated, documentsCreated, skipped)
    }

    private companion object {
        val log = LoggerFactory.getLogger(SeedService::class.java)
        const val CORPUS = "seed/corpus.json"
        const val DOCUMENTS = "seed/documents"
    }
}
