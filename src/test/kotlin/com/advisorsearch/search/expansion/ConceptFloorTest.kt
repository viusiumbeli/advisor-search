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
 * Several kinds of row are documented rather than asserted away, each asserted to stay as listed so
 * the record cannot drift: paraphrases measured under the floor (intent is a thinner signal than topic
 * in this embedding space); sibling concepts that ride in within the ratio on one query, and the whole
 * financial-situation cluster, whose members ride each other by nature; a paraphrase another rule
 * outranks; and a two-concept query whose second concept sits under the ratio. Interleaving keeps the
 * intended concept's expansion first, and the semantic floors still apply to whatever the extra probes
 * find. The sibling matrix printed at the end is where the cluster's numbers are read from.
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
                    "proof of residence",
                    "residential address evidence for customer due diligence",
                ),
            "evidence of identity" to
                listOf(
                    "how does she prove who she is",
                    "confirm the client is who he says he is",
                    "what ID do we need from him",
                    "verify the customer's identity",
                ),
            "evidence of income" to
                listOf(
                    "what shows how much he earns",
                    "documents confirming annual earnings",
                    "income verification for the mortgage application",
                    "evidence of the income declared",
                ),
            "authority to act for another person" to
                listOf(
                    "who makes decisions if he can no longer manage his affairs",
                    "someone to run his finances if he becomes unable to",
                    "who can act for a client if they lose capacity",
                    "can the attorneys act while he still has capacity",
                    "is the LPA registered with the Office of the Public Guardian",
                    "who is allowed to give instructions on his behalf",
                    "can the executor deal with the account",
                ),
            "source of funds and source of wealth" to
                listOf(
                    "where did the money she is investing come from",
                    "how did he build up his wealth",
                    "evidence of where the invested money came from",
                ),
            "beneficial ownership of a company or trust" to
                listOf("who ultimately owns the business", "the ownership structure behind the company"),
            "evidence of property ownership" to listOf("does he own his home", "evidence that the property is in her name"),
            "evidence of assets" to listOf("what savings and investments does he hold", "statement of her savings and investments"),
            "evidence of liabilities" to listOf("which debts are outstanding", "evidence of outstanding borrowing"),
            "evidence of expenditure" to listOf("what are her monthly outgoings", "evidence of committed spending"),
            "repayment strategy for an interest-only mortgage" to
                listOf(
                    "how will the mortgage be repaid at the end of the term",
                    "evidence of the repayment vehicle",
                    "repayment strategy for the interest-only mortgage",
                ),
        )

    /** Measured under the absolute floor and left there: the limit of the normaliser, stated rather than tuned around. */
    private val belowTheFloor =
        setOf(
            "what can he send to show where he lives",
            "what ID do we need from him",
            "is the LPA registered with the Office of the Public Guardian",
        )

    /**
     * Paraphrases another rule outranks — the intended concept still fires, second. Listed like the
     * paraphrases under the floor: the near-synonym cluster (income, assets, liabilities, expenditure)
     * is the design's known limit, measured in the sibling matrix below and written up in
     * docs/search-design.md; the lever is a phrasing or a hierarchy of rules, never a lower ratio.
     */
    private val outrankedBy = mapOf("someone to run his finances if he becomes unable to" to "evidence of expenditure")

    /**
     * The financial-situation cluster: COBS 9A.2.7R's "regular income", "assets", "regular financial
     * commitments", with the two rules whose evidence is the same documents (a mortgage repayment
     * vehicle is an asset; a property is one). Their exact phrasings sit at 0.70–0.73 of each other
     * (the matrix below) and their natural paraphrases ride each other in within the ratio, so a rider
     * inside the cluster is allowed and printed rather than listed one row at a time. That is the
     * design's known limit; the resolution proposed in docs/search-design.md is a hierarchy, not a lower ratio.
     */
    private val crowdedCluster =
        setOf(
            "evidence of income",
            "evidence of assets",
            "evidence of liabilities",
            "evidence of expenditure",
            "repayment strategy for an interest-only mortgage",
            "evidence of property ownership",
        )

    /** Sibling concepts measured within the ratio on a single-concept query, allowed and listed. */
    private val siblingsAllowed =
        mapOf(
            "confirm the client is who he says he is" to setOf("evidence of address", "evidence of income"),
            "evidence of where the invested money came from" to
                setOf(
                    "evidence of income",
                    "evidence of assets",
                    "evidence of expenditure",
                ),
            "where did the money she is investing come from" to setOf("evidence of assets"),
            "residential address evidence for customer due diligence" to setOf("evidence of property ownership"),
            "the ownership structure behind the company" to setOf("evidence of property ownership"),
        )

    /** A phrasing inside a longer sentence — what substring matching used to handle. */
    private val sentences =
        mapOf(
            "I need proof of address for Jane Roe" to "evidence of address",
            "please find the power of attorney for Mr Ashworth-Bell" to "authority to act for another person",
            "does the file hold a proof of identity for the joint applicant" to "evidence of identity",
            "we still need proof of income for the Lindqvist remortgage" to "evidence of income",
            "please confirm the source of funds for Mrs Moreau's GIA" to "source of funds and source of wealth",
        )

    /** Requirements named together, as an onboarding or mortgage file lists them (JMLSG 5.3.99–5.3.101 for the attorneys). */
    private val twoConcepts =
        mapOf(
            "proof of address and proof of identity" to setOf("evidence of address", "evidence of identity"),
            "identity and address documents for onboarding" to setOf("evidence of address", "evidence of identity"),
            "proof of address and proof of income for the mortgage application" to setOf("evidence of address", "evidence of income"),
            "identity documents and proof of income for onboarding" to setOf("evidence of identity", "evidence of income"),
        )

    /**
     * Two-concept queries whose second concept measures under the ratio and is left there: an exact
     * phrasing of one rule ("power of attorney", 0.81) sets a bar the other half does not reach (0.74 of
     * it). Listed, like the paraphrases under the floor; the lever is a phrasing, never a lower ratio.
     */
    private val secondUnderTheRatio =
        mapOf("power of attorney and proof of identity for the attorneys" to "authority to act for another person")

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
            "proceeds of the Bath property sale",
        )

    /**
     * The regulation's own vocabulary and the firm's file references, as a compliance reader might type
     * them. Reported, not policed: umbrella terms (KYC, CDD, AML) name identity and address at once, and
     * "PoA" is proof of address in the onboarding checklist's own filing (POA-01) but a power of attorney
     * everywhere else — so none of them is a phrasing, and the table shows what each one does reach.
     */
    private val regulatoryVocabulary =
        listOf(
            "customer due diligence identity documents",
            "KYC documents on file",
            "AML checks on the new client",
            "ID&V for the joint applicant",
            "verify the customer's residential address",
            "PoA",
            "POA-01",
            "CDD",
            "CETV",
        )

    /**
     * Golden queries about nothing in the lexicon, the golden client queries, nonsense, reference codes,
     * and the phrasings of the removed "life cover paid on death" rule: none may fire.
     */
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
            "inconsistent answers on the risk questionnaire",
            "which countries is he tax resident in",
            "payout when the policyholder dies",
            "what the family receives when she dies",
            "sum paid out on death",
            "death benefit",
            "life cover",
            "send me the death benefit details on the Okonkwo policy",
            "when did the policyholder die",
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
                    val expectedFirst = outrankedBy[query] ?: concept
                    if (fired.firstOrNull() != expectedFirst) failures += "  \"$query\" -> expected \"$expectedFirst\" first, fired $fired"
                    if (query in outrankedBy &&
                        concept !in fired
                    ) {
                        failures += "  \"$query\" is listed as outranked but no longer fires \"$concept\" at all"
                    }
                    val allowed =
                        siblingsAllowed.getOrDefault(query, emptySet()) + if (concept in crowdedCluster) crowdedCluster else emptySet()
                    val extras = fired.toSet() - concept - expectedFirst - allowed
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
        secondUnderTheRatio.forEach { (query, first) ->
            val fired = expander.expand(query).concepts.map { it.concept }
            print(query, expander.similarities(query), fired)
            if (fired != listOf(first)) failures += "  \"$query\" is listed with its second concept under the ratio but fired $fired"
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

    /**
     * The sibling matrix: for every pair of rules, the highest score any exact phrasing of the row's
     * rule reaches against the column's rule, as a share of its own score (1.00 for an exact phrasing).
     * A cell at or above the ratio (0.75) means a phrasing of the row fires the column too; cells just
     * under it are the margins a new rule or phrasing has to respect. Reported every run because the
     * limit this design has — near-synonym requirements crowd — is read from here.
     */
    @Test
    fun `the sibling matrix between every pair of rules, reported`() {
        val concepts = lexicon.rules.map { it.concept }
        val labels = concepts.map { it.take(LABEL) }
        println("%-${LABEL}s ${labels.joinToString(" ") { "%-${LABEL}s".format(it) }}".format("row fires column at"))
        lexicon.rules.forEach { rule ->
            val shares =
                (listOf(rule.concept) + rule.paraphrases)
                    .map { phrasing -> expander.similarities(phrasing) }
                    .fold(concepts.associateWith { 0.0 }) { best, scored ->
                        val own = scored.first { it.concept == rule.concept }.similarity
                        best.mapValues { (concept, share) -> maxOf(share, scored.first { it.concept == concept }.similarity / own) }
                    }
            println(
                "%-${LABEL}s ${concepts.joinToString(
                    " ",
                ) { concept -> "%-${LABEL}s".format(if (concept == rule.concept) "-" else "%.2f".format(shares.getValue(concept))) }}"
                    .format(rule.concept.take(LABEL)),
            )
        }
    }

    @Test
    fun `the regulation's own vocabulary is reported, not policed`() {
        regulatoryVocabulary.forEach { query ->
            print(query, expander.similarities(query), expander.expand(query).concepts.map { it.concept })
        }
    }

    private companion object {
        /** Column width of the sibling matrix. */
        const val LABEL = 14
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
