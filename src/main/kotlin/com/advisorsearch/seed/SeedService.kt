package com.advisorsearch.seed

import com.advisorsearch.clients.ClientService
import com.advisorsearch.clients.CreateClientRequest
import com.advisorsearch.documents.CreateDocumentRequest
import com.advisorsearch.documents.DocumentRepository
import com.advisorsearch.documents.DocumentService
import com.advisorsearch.seed.corpus.Corpus
import com.advisorsearch.support.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
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
    private val loading = AtomicBoolean(false)

    /** Merges rather than refuses, which [seedEmptyCorpus] relies on — do not point the runner at that one. */
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

    /**
     * The on-demand path, for the deployed instance that starts empty. [seed] itself stays unguarded:
     * its other callers — the startup runner against a volume that may already hold the corpus, and
     * the seeded tests — depend on it merging rather than refusing.
     *
     * Refuses unless the corpus is untouched, and the check has to come first, because [SeedSummary]
     * cannot express the difference: a second run reports `(0, 0, 20)`, which is what loading nothing
     * looks like too. The refusal is not fussiness about duplicates. Twenty unrelated documents in
     * someone else's corpus do not merely add rows to a result page — the semantic floor is relative,
     * so they can lift the cut-off past the document that was being looked for, and a search that
     * answers nothing reads as a broken search.
     */
    fun seedEmptyCorpus(): SeedSummary {
        // Emptiness is checked, not locked, and the window between the check and the first insert is
        // real: this request runs for seconds with nothing to show, which is exactly when someone
        // clicks again or opens a second tab. The second caller would find no client yet, pass the
        // check, and race the first — clients would merge on email, but nothing enforces one title
        // per client, so the corpus would end up with duplicated documents. JVM-local on purpose:
        // one instance is the deployment shape, and a replicated one would need an advisory lock.
        if (!loading.compareAndSet(false, true)) {
            throw ConflictException("The demo corpus is already loading. Wait for that request to answer, then search.")
        }
        try {
            if (clients.anyExist()) {
                throw ConflictException(
                    "This instance already holds data, and the demo corpus would add ten clients and twenty " +
                        "documents that compete with it in search results. Only an empty instance can be seeded.",
                )
            }
            return seed()
        } finally {
            loading.set(false)
        }
    }
}
