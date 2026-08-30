package com.advisorsearch.search.expansion

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.clients.ClientRepository
import com.advisorsearch.clients.NewClient
import com.advisorsearch.config.SearchProperties
import com.advisorsearch.documents.CreateDocumentRequest
import com.advisorsearch.documents.DocumentService
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.search.DocumentMatch
import com.advisorsearch.search.DocumentSearchRepository
import com.advisorsearch.search.bestByDocument
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The lexicon's expansions are the document types a UK adviser's file holds as evidence for a
 * requirement, and the seeded demo corpus lacks most of them — it has no payslip, no passport, no
 * court order. So "the probe reaches nothing seeded" says nothing about the probe. What does: given
 * a document of that type, does the probe put it on the semantic arm's page? This test holds one
 * short synthetic document per type the seed lacks (`fixtures/evidence`), ingests them through the
 * real path inside a transaction that rolls back — they are never seeded into the demo — and asserts
 * that every expansion in the lexicon places its own document within the semantic floors. The four
 * types the seed does hold are checked against the seeded document instead.
 */
@Transactional
class EvidenceFixtureTest(
    private val documentService: DocumentService,
    private val clients: ClientRepository,
    private val documents: DocumentSearchRepository,
    private val embeddings: EmbeddingService,
    private val expander: QueryExpander,
    private val properties: SearchProperties,
    objectMapper: ObjectMapper,
) : SeededIntegrationTest() {
    private val lexicon: ExpansionLexicon = objectMapper.readValue(ClassPathResource("search/query-expansions.json").inputStream)
    private val manifest: Manifest = objectMapper.readValue(ClassPathResource("fixtures/evidence/manifest.json").inputStream)

    /**
     * Probes measured to miss their document and left there, like ConceptFloorTest's paraphrases under
     * the floor: the lever is the semantic arm's relative floor, not the probe's wording. "pension
     * statement" is what MCOB 11.6.15G(2) and advisers call the document; against the seeded scheme
     * statement it loses to a suitability report whose title is about pensions (0.68 against 0.46) and
     * the page's floor of 0.70 × best cuts the statement — a near-synonym title outranks the real thing.
     */
    private val knownMisses = mapOf("pension statement" to "Annual Benefit Statement")

    @Test
    fun `every expansion places its own document on the semantic page when that document is present`() {
        ingestFixtures()
        val failures = mutableListOf<String>()

        lexicon.rules.forEach { rule ->
            println("--- ${rule.concept}")
            rule.expansions.zip(embeddings.embedQueries(rule.expansions)).forEach { (expansion, vector) ->
                val target = targetOf(expansion)
                val page = page(documents.semanticSearch(vector, CANDIDATES))
                val rank = page.indexOfFirst { it.reference.title.contains(target, ignoreCase = true) } + 1
                val hit = page.getOrNull(rank - 1)
                println(
                    "%-44s -> %-62s %s of %d  %s  best %.3f".format(
                        "'$expansion'",
                        target,
                        if (rank > 0) "rank $rank" else "MISSING",
                        page.size,
                        hit?.let { "%.3f".format(it.score) } ?: "-",
                        page.firstOrNull()?.score ?: 0.0,
                    ),
                )
                when {
                    expansion in knownMisses && rank > 0 ->
                        failures +=
                            "  '$expansion' is listed as a known miss but now places \"$target\" — take it off the list"
                    expansion !in knownMisses && rank == 0 ->
                        failures +=
                            "  '$expansion' does not place \"$target\" on its page: ${page.map { it.reference.title }}"
                }
            }
        }

        assertTrue(failures.isEmpty(), "probes and their documents:\n" + failures.joinToString("\n"))
    }

    /**
     * Reported, not asserted: the page each rule's concept produces with the fixtures present. Only the
     * first four expansions run for a one-concept query, so this is where the probe budget shows —
     * types listed fifth and later are on the accepted list but not on today's page.
     */
    @Test
    fun `the page each rule produces with every document type present, reported`() {
        ingestFixtures()

        lexicon.rules.forEach { rule ->
            val expansion = expander.expand(rule.concept)
            val page = page(expansion.vectors.flatMap { documents.semanticSearch(it, CANDIDATES) }.bestByDocument())
            println("--- \"${rule.concept}\" runs ${expansion.texts.drop(1)}; page of ${page.size}:")
            page.forEachIndexed { index, match -> println("  %2d. %-66s %.3f".format(index + 1, match.reference.title, match.score)) }
            val notRun = rule.expansions.drop(expansion.texts.size - 1)
            if (notRun.isNotEmpty()) println("  listed but not run for a one-concept query (MAX_PROBES): $notRun")
        }
    }

    private fun targetOf(expansion: String): String =
        manifest.fixtures.firstOrNull { it.expansion == expansion }?.title
            ?: manifest.seeded[expansion]
            ?: fail("'$expansion' has neither a fixture in fixtures/evidence/manifest.json nor a seeded document to be measured against")

    private fun ingestFixtures() {
        val client = clients.insert(NewClient("Evidence", "Fixtures", "fixtures@evidence.example", null, emptyList()))
        manifest.fixtures.forEach { fixture ->
            val content = ClassPathResource("fixtures/evidence/${fixture.file}").inputStream.bufferedReader().readText()
            documentService.create(client.id, CreateDocumentRequest(fixture.title, content))
        }
    }

    /** The semantic arm's own page: the absolute floor, then the share of the best hit, as SearchService applies them. */
    private fun page(matches: List<DocumentMatch>): List<DocumentMatch> {
        val best = matches.maxOfOrNull { it.score } ?: return emptyList()
        val floor = maxOf(properties.semanticFloor, best * properties.semanticFloorRatio)
        return matches.filter { it.score >= floor }.sortedByDescending { it.score }
    }

    private data class Manifest(
        val fixtures: List<Fixture>,
        val seeded: Map<String, String>,
    )

    private data class Fixture(
        val expansion: String,
        val title: String,
        val file: String,
    )

    private companion object {
        /** Larger than the corpus, so the page sees every document rather than a shortlist. */
        const val CANDIDATES = 400
    }
}
