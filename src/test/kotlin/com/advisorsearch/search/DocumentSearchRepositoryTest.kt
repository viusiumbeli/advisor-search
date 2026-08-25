package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.embedding.EmbeddingService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The semantic arm's shortlist is counted in documents. Counting chunks instead is the version that
 * reads as equivalent and is not: the seeded reports run to 22 chunks each, so two or three of them
 * fill a chunk-shaped window and every shorter document becomes unreachable however well it matches
 * — including the electricity bill the brief's own example is about.
 */
class DocumentSearchRepositoryTest(
    private val documents: DocumentSearchRepository,
    private val embeddings: EmbeddingService,
) : SeededIntegrationTest() {
    @Test
    fun `the shortlist holds as many documents as it was asked for`() {
        val vector = embeddings.embedQuery("pension")

        val matches = documents.semanticSearch(vector, candidateDocuments = 3)

        assertEquals(3, matches.size, "asked for three documents, got ${matches.map { it.reference.title }}")
    }

    @Test
    fun `each document appears once, represented by its own nearest chunk`() {
        val vector = embeddings.embedQuery("retirement income planning")

        val matches = documents.semanticSearch(vector, candidateDocuments = 20)

        val ids = matches.map { it.reference.id }
        assertTrue(matches.size > 1, "expected the seeded corpus to offer several documents")
        assertEquals(ids.distinct(), ids, "a document took more than one place in the shortlist")
        assertEquals(
            matches.sortedByDescending { it.score },
            matches,
            "the shortlist must arrive in relevance order; fusion reads it as a ranking",
        )
    }

    @Test
    fun `a short document is reachable beside the long ones`() {
        // The bill is three chunks against reports of twenty-two, and this is the query the brief
        // asks about. Five, not thirty: a shortlist wider than the corpus would hold the bill
        // whatever the query did, and prove nothing.
        val vector = embeddings.embedQuery("utility bill")

        val matches = documents.semanticSearch(vector, candidateDocuments = 5)

        assertTrue(
            matches.any { it.reference.title.contains("Electricity Account") },
            "the electricity bill was crowded out of the shortlist; got ${matches.map { it.reference.title }}",
        )
    }
}
