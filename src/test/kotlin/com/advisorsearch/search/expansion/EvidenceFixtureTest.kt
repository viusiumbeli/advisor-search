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
import org.springframework.jdbc.core.simple.JdbcClient
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
    private val jdbc: JdbcClient,
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

    /**
     * Phrases carried by no document in the evidence set, kept on a stated source and listed here so
     * they cannot rot: if one starts being carried, this map is what says so. Empty today.
     */
    private val unprovenPhrases = mapOf<String, String>()

    @Test
    fun `every document type places its own document on the semantic page when that document is present`() {
        ingestFixtures()
        val failures = mutableListOf<String>()

        lexicon.rules.forEach { rule ->
            println("--- ${rule.concept}")
            rule.documentTypes.zip(embeddings.embedQueries(rule.documentTypes)).forEach { (expansion, vector) ->
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
            val notRun = rule.documentTypes.drop(expansion.texts.size - 1)
            if (notRun.isNotEmpty()) println("  listed but not run for a one-concept query (MAX_PROBES): $notRun")
        }
    }

    /**
     * The other half of the lexicon. A document type is what an advisor calls the document, and the
     * semantic arm handles it; a document phrase is what the document says, and it goes to the arms
     * that match text — where it is only worth anything if it really is the document's own wording.
     * Four gates, each one a failure this measured before it shipped:
     *
     * 1. At least two lexemes after stemming. "it is ordered that" collapses to the single lexeme
     *    `order` and reaches unrelated documents; a word count does not catch that.
     * 2. It must not match any rule's concept, paraphrases or document types. `firearm certificate`
     *    and the type `firearms certificate` both parse to `'firearm' <-> 'certif'`, so a string
     *    comparison passes it — and it then returns the onboarding checklist, which is exactly the
     *    type-name failure this split exists to remove.
     * 3. It must be carried by one of this rule's own documents, and by no more than [MAX_CARRIERS] of
     *    the evidence set in all. Both halves are measured: every phrase that ships is carried by one
     *    to three of the sixty-five documents, while the first candidates rejected for breadth were
     *    `account holder` at five — it outranks the electricity bill on the address page — `direct
     *    debit` at eight and `date of birth` at nine. Carriage is read from content only: the
     *    searchable column weights titles at `A`, and a phrase proved by a title this repository
     *    wrote proves nothing. Carriers belonging to another rule's documents are printed, not
     *    failed — a bank statement carrying "closing balance" is liquid-asset evidence as well as
     *    address evidence, and the lexicon says so twice on purpose.
     * 4. It must reach that document through the real arm. Stemming makes that a separate fact.
     */
    @Test
    fun `every document phrase is the document's own wording, not the requirement's`() {
        ingestFixtures()
        val failures = mutableListOf<String>()
        val requirementWords =
            lexicon.rules.flatMap { listOf(it.concept) + it.paraphrases + it.documentTypes }.joinToString("|")

        lexicon.rules.forEach { rule ->
            println("--- ${rule.concept}")
            if (rule.documentPhrases.isEmpty()) failures += "  ${rule.concept} declares no document phrase"
            val ownTitles = titlesOf(rule)
            rule.documentPhrases.forEach { phrase ->
                val nodes = scalar("SELECT numnode(phraseto_tsquery('english', :phrase))", phrase)
                val restated =
                    jdbc
                        .sql(
                            """
                            SELECT t FROM unnest(string_to_array(:texts, '|')) t
                            WHERE to_tsvector('english', t) @@ phraseto_tsquery('english', :phrase)
                            """.trimIndent(),
                        ).param("texts", requirementWords)
                        .param("phrase", phrase)
                        .query(String::class.java)
                        .list()
                val carriers = carriersOf(phrase)
                val reached = documents.phraseSearch(listOf(phrase), CANDIDATES).map { it.reference.title }
                println(
                    "%-46s %d nodes | %d of %d carry it%s: %s".format(
                        "'$phrase'",
                        nodes,
                        carriers.size,
                        corpusSize(),
                        if (carriers.any { it !in ownTitles }) ", one on another rule" else "",
                        carriers.joinToString(", ").ifEmpty { "nothing" },
                    ),
                )

                if (nodes < LEXEMES) failures += "  '$phrase' is one lexeme after stemming, so it matches far more than itself"
                if (restated.isNotEmpty()) failures += "  '$phrase' restates the requirement's own words: $restated"
                val own = carriers.filter { it in ownTitles }
                when {
                    phrase in unprovenPhrases && carriers.isNotEmpty() ->
                        failures += "  '$phrase' is listed as unproven but is now carried by $carriers — take it off the list"
                    phrase !in unprovenPhrases && own.isEmpty() ->
                        failures += "  '$phrase' is carried by no document this rule names; it has $carriers"
                    carriers.size > MAX_CARRIERS ->
                        failures +=
                            "  '$phrase' is carried by ${carriers.size} of ${corpusSize()} documents, too broad for an arm that exists for precision: $carriers"
                    own.isNotEmpty() && reached.none { it in own } ->
                        failures += "  '$phrase' does not reach its own carrier through the arm; it reached $reached"
                }
            }
        }

        assertTrue(failures.isEmpty(), "document phrases:\n" + failures.joinToString("\n"))
    }

    /**
     * The control on the whole idea. The onboarding checklist is the seeded document that speaks the
     * *requirement's* language — "proof of address", "accepted documents" — which is why a type name
     * finds it and the bill it is about goes missing. No rule's phrases may reach it.
     */
    @Test
    fun `no rule's phrases reach the document that merely describes the requirement`() {
        ingestFixtures()
        val failures = mutableListOf<String>()

        lexicon.rules.forEach { rule ->
            val page = documents.phraseSearch(rule.documentPhrases, CANDIDATES).map { it.reference.title }
            println("--- \"${rule.concept}\" phrase page: ${page.take(6)}")
            val checklist = page.filter { it.contains("Onboarding Checklist") }
            if (checklist.isNotEmpty()) {
                failures +=
                    "  ${rule.concept} reaches $checklist, which describes the requirement rather than evidencing it"
            }
        }

        assertTrue(failures.isEmpty(), "phrase pages:\n" + failures.joinToString("\n"))
    }

    /** Every title this rule's own document types name, fixtures and seeded alike. */
    private fun titlesOf(rule: ExpansionRule): Set<String> =
        rule.documentTypes
            .mapNotNull { type -> manifest.fixtures.firstOrNull { it.expansion == type }?.title ?: manifest.seeded[type] }
            .flatMapTo(mutableSetOf()) { target -> allTitles().filter { it.contains(target, ignoreCase = true) } }

    /** Content only: the searchable column weights the title at `A`, so a title match would prove nothing. */
    private fun carriersOf(phrase: String): Set<String> =
        jdbc
            .sql("SELECT title FROM documents WHERE to_tsvector('english', content) @@ phraseto_tsquery('english', :phrase) ORDER BY title")
            .param("phrase", phrase)
            .query(String::class.java)
            .list()
            .filterNotNullTo(mutableSetOf())

    private fun allTitles(): List<String> =
        jdbc
            .sql("SELECT title FROM documents")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun corpusSize(): Int = scalar("SELECT count(*) FROM documents", null)

    private fun scalar(
        sql: String,
        phrase: String?,
    ): Int =
        jdbc
            .sql(sql)
            .let { if (phrase == null) it else it.param("phrase", phrase) }
            .query(Int::class.java)
            .single()

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
        /** A phrase must survive stemming as at least two lexemes: `'a' <-> 'b'` is three nodes. */
        const val LEXEMES = 3

        /** Measured: what ships is carried by one to three documents, and the first rejection was five. */
        const val MAX_CARRIERS = 3

        /** Larger than the corpus, so the page sees every document rather than a shortlist. */
        const val CANDIDATES = 400
    }
}
