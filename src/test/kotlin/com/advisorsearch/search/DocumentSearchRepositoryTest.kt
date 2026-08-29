package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.embedding.SparseVector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both learned arms' shortlists are counted in documents. Counting chunks instead is the version that
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

    @Test
    fun `the sparse shortlist is never wider than asked, and only holds documents sharing a term`() {
        val vector = embeddings.encodeQueriesSparsely(listOf("pension")).single()

        val matches = documents.sparseSearch(vector, candidateDocuments = 3)

        assertTrue(matches.size in 1..3, "asked for three documents, got ${matches.map { it.reference.title }}")
        assertTrue(matches.all { it.score > 0.0 }, "a document sharing no term must not be shortlisted")
    }

    @Test
    fun `each document appears once in the sparse shortlist, in score order`() {
        val vector = embeddings.encodeQueriesSparsely(listOf("retirement income planning")).single()

        val matches = documents.sparseSearch(vector, candidateDocuments = 20)

        val ids = matches.map { it.reference.id }
        assertTrue(matches.size > 1, "expected the seeded corpus to offer several documents")
        assertEquals(ids.distinct(), ids, "a document took more than one place in the sparse shortlist")
        assertEquals(matches.sortedByDescending { it.score }, matches, "the shortlist must arrive in relevance order")
    }

    @Test
    fun `a short document is reachable by the sparse arm beside the long ones`() {
        val vector = embeddings.encodeQueriesSparsely(listOf("electricity statement")).single()

        val matches = documents.sparseSearch(vector, candidateDocuments = 5)

        assertTrue(
            matches.any { it.reference.title.contains("Electricity Account") },
            "the electricity bill was crowded out of the sparse shortlist; got ${matches.map { it.reference.title }}",
        )
    }

    @Test
    fun `a query sharing no term with the corpus finds nothing`() {
        // Vocabulary id 1 is [unused0]: no text ever activates it, so the inner product is zero
        // everywhere and the HAVING keeps every document out.
        val matches = documents.sparseSearch(SparseVector(intArrayOf(1), floatArrayOf(1f)), candidateDocuments = 30)

        assertEquals(emptyList(), matches)
    }
}
