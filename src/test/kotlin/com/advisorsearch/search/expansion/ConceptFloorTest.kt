package com.advisorsearch.search.expansion

import com.advisorsearch.IntegrationTest
import com.advisorsearch.config.SearchProperties
import com.advisorsearch.experiments.LEXICAL_PROBES
import com.advisorsearch.experiments.NONSENSE_PROBES
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertTrue

/**
 * Guards `search.concept-floor` and `search.concept-floor-ratio` from both sides and prints the
 * evidence behind them, the way SemanticFloorTest does for the cosine floors. A query reaches a
 * lexicon rule when its vector comes within the absolute floor of one of the rule's phrasings and
 * within the ratio of the best rule's score; the tables below are what those two numbers were read
 * from. Full numbers in docs/search-design.md, "Matching a query to a concept".
 *
 * Two kinds of row are documented rather than asserted away. Paraphrases measured under the floor
 * are listed with their score: intent is a thinner signal than topic in this embedding space, and a
 * question that shares few words with any phrasing does not reach it. Sibling concepts that ride in
 * within the ratio on one query are listed too; interleaving keeps the intended concept's expansion
 * first, and the semantic floors still apply to whatever the extra probes find.
 */
class ConceptFloorTest(
    private val expander: QueryExpander,
    private val properties: SearchProperties,
    private val objectMapper: ObjectMapper,
) : IntegrationTest() {
    private val lexicon: ExpansionLexicon =
        objectMapper.readValue(ClassPathResource("search/query-expansions.json").inputStream, ExpansionLexicon::class.java)

    /** Paraphrases of each requirement that share no phrase with the lexicon. Each must fire its own concept first. */
    private val paraphrases =
        mapOf(
            "evidence of address" to
                listOf(
                    "documents that show where the client lives",
                    "something official with her home address on it",
                    "what can he send to show where he lives",
                    "confirm the client's residential address",
                ),
            "evidence of identity" to
                listOf("how does she prove who she is", "confirm the client is who he says he is", "what ID do we need from him"),
            "evidence of income" to listOf("what shows how much he earns", "documents confirming annual earnings"),
            "acting for someone who has lost capacity" to
                listOf(
                    "who makes decisions if he can no longer manage his affairs",
                    "someone to run his finances if he becomes unable to",
                    "who can act for a client if they lose capacity",
                ),
            "life cover paid on death" to
                listOf("payout when the policyholder dies", "what the family receives when she dies", "sum paid out on death"),
        )

    /** Measured under the absolute floor and left there: the limit of the normaliser, stated rather than tuned around. */
    private val belowTheFloor =
        setOf("what can he send to show where he lives", "what ID do we need from him", "what the family receives when she dies")

    /** Sibling concepts measured within the ratio on a single-concept query, allowed and listed. */
    private val siblingsAllowed =
        mapOf("confirm the client is who he says he is" to setOf("evidence of address", "evidence of income"))

    /** A phrasing inside a longer sentence — what substring matching used to handle. */
    private val sentences =
        mapOf(
            "I need proof of address for Jane Roe" to "evidence of address",
            "please find the power of attorney for Mr Ashworth-Bell" to "acting for someone who has lost capacity",
            "does the file hold a proof of identity for the joint applicant" to "evidence of identity",
            "send me the death benefit details on the Okonkwo policy" to "life cover paid on death",
        )

    private val twoConcepts =
        mapOf(
            "proof of address and proof of identity" to setOf("evidence of address", "evidence of identity"),
            "identity and address documents for onboarding" to setOf("evidence of address", "evidence of identity"),
            "power of attorney and life cover for the estate" to
                setOf("acting for someone who has lost capacity", "life cover paid on death"),
        )

    /**
     * Questions about a concept's own topic that ask for a value rather than for evidence. Reported,
     * not policed: the evidence documents for a requirement are the documents that state the fact, so
     * "what is the client's current address" reaching the address rule is the right answer.
     */
    private val contentQuestions =
        listOf(
            "what is the client's current address",
            "update the client address",
            "which clients live in Bristol",
            "postcode for Mr Delacroix-Whitfield",
            "what is the client's date of birth",
            "client's passport number on file",
            "what is his annual salary",
            "how much does she earn",
            "who is the attorney named on the LPA",
            "when did the policyholder die",
        )

    /** Golden queries about nothing in the lexicon, the golden client queries, nonsense and reference codes: none may fire. */
    private val unrelated =
        listOf(
            "utility bill",
            "retirement income planning",
            "drawdown sustainability",
            "Kestrel Global Infrastructure",
            "inheritance tax on gifts",
            "rental yield",
            "share options vesting",
            "anti money laundering checks",
            "capital gains on a second home",
            "trustee duties",
            "energy performance certificate",
            "double taxation treaty",
            "ISA transfer",
            "break clause",
            "electricity supplier statement",
            "meter readings kWh unit rate",
            "tax agreement between two countries",
            "AldgateWealth",
            "aldgatewealth.example",
            "raghunathan",
            "Delacroix-Whitfeld",
            "retired teacher",
            "Okonkwo",
            "buy-to-let",
        ) + NONSENSE_PROBES + LEXICAL_PROBES

    @Test
    fun `every paraphrase reaches its concept first, or is listed under the floor`() {
        val failures = mutableListOf<String>()
        var weakestFiring = 1.0

        paraphrases.forEach { (concept, queries) ->
            queries.forEach { query ->
                val fired = expander.expand(query).concepts.map { it.concept }
                val scored = expander.similarities(query)
                print(query, scored, fired)
                if (query in belowTheFloor) {
                    if (fired.isNotEmpty()) failures += "  \"$query\" is listed under the floor but fired $fired"
                } else {
                    weakestFiring = minOf(weakestFiring, scored.first().similarity)
                    if (fired.firstOrNull() != concept) failures += "  \"$query\" -> expected \"$concept\" first, fired $fired"
                    val extras = fired.drop(1).toSet() - siblingsAllowed.getOrDefault(query, emptySet())
                    if (extras.isNotEmpty()) failures += "  \"$query\" dragged in $extras"
                }
            }
        }

        println("weakest firing paraphrase: %.4f against a floor of %.2f".format(weakestFiring, properties.conceptFloor))
        assertTrue(failures.isEmpty(), "paraphrases:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `a phrasing inside a longer sentence still reaches its concept`() {
        val failures = mutableListOf<String>()

        sentences.forEach { (query, concept) ->
            val fired = expander.expand(query).concepts.map { it.concept }
            print(query, expander.similarities(query), fired)
            if (fired.firstOrNull() != concept) failures += "  \"$query\" -> expected \"$concept\", fired $fired"
        }

        assertTrue(failures.isEmpty(), "sentences:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `no query about something else reaches any concept`() {
        val leaked = mutableListOf<String>()
        var closest = 0.0 to ""

        unrelated.forEach { query ->
            val fired = expander.expand(query).concepts
            val scored = expander.similarities(query)
            print(query, scored, fired.map { it.concept })
            if (scored.first().similarity > closest.first) closest = scored.first().similarity to "\"$query\" -> ${scored.first().concept}"
            if (fired.isNotEmpty()) leaked += "  \"$query\" fired ${fired.map { it.concept }}"
        }

        println(
            "closest unrelated query: %s at %.4f against a floor of %.2f".format(closest.second, closest.first, properties.conceptFloor),
        )
        assertTrue(leaked.isEmpty(), "the floor is letting unrelated queries through:\n" + leaked.joinToString("\n"))
    }

    @Test
    fun `a rule's own phrasings fire only that rule`() {
        val failures = mutableListOf<String>()
        var strongestSibling = 0.0

        lexicon.rules.forEach { rule ->
            (listOf(rule.concept) + rule.paraphrases).forEach { phrasing ->
                val scored = expander.similarities(phrasing)
                val sibling = scored.first { it.concept != rule.concept }
                strongestSibling = maxOf(strongestSibling, sibling.similarity / scored.first().similarity)
                val fired = expander.expand(phrasing).concepts.map { it.concept }
                if (fired != listOf(rule.concept)) failures += "  \"$phrasing\" fired $fired"
            }
        }

        println(
            "strongest sibling share of an exact phrasing's own score: %.4f against a ratio of %.2f".format(
                strongestSibling,
                properties.conceptFloorRatio,
            ),
        )
        assertTrue(failures.isEmpty(), "exact phrasings:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `a query naming two concepts fires both`() {
        val failures = mutableListOf<String>()
        var weakestSecond = 1.0

        twoConcepts.forEach { (query, intended) ->
            val scored = expander.similarities(query)
            val fired = expander.expand(query).concepts.map { it.concept }
            print(query, scored, fired)
            weakestSecond = minOf(weakestSecond, scored[1].similarity / scored[0].similarity)
            if (fired.toSet() != intended) failures += "  \"$query\" -> expected $intended, fired $fired"
        }

        println(
            "weakest second concept, as a share of the first: %.4f against a ratio of %.2f".format(
                weakestSecond,
                properties.conceptFloorRatio,
            ),
        )
        assertTrue(failures.isEmpty(), "two-concept queries:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `content questions about a concept's own topic are reported, not policed`() {
        contentQuestions.forEach { query -> print(query, expander.similarities(query), expander.expand(query).concepts.map { it.concept }) }
    }

    private fun print(
        query: String,
        scored: List<ConceptMatch>,
        fired: List<String>,
    ) {
        val best = scored[0]
        val runnerUp = scored[1]
        println(
            "%-62s %.4f %-42s via '%s' | next %.4f %-42s ratio %.2f | fired %s".format(
                "\"$query\"",
                best.similarity,
                best.concept,
                best.phrasing,
                runnerUp.similarity,
                runnerUp.concept,
                runnerUp.similarity / best.similarity,
                fired,
            ),
        )
    }
}
