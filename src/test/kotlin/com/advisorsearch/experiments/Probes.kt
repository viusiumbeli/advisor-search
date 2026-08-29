package com.advisorsearch.experiments

/**
 * Probe query to a prefix of the expected seed document's file name, shared by both model
 * experiments so a dense and a sparse table can be read side by side.
 */
internal val PROBES =
    listOf(
        "address proof" to "electricity-account-statement",
        "proof of address" to "electricity-account-statement",
        "utility bill" to "electricity-account-statement",
        "who can act for a client if they lose capacity" to "lasting-power-of-attorney",
        "retirement income planning" to "suitability-report",
        "inheritance tax on gifts" to "meeting-notes-estate-planning",
        "share options vesting" to "share-option-scheme",
        "rental yield" to "buy-to-let-portfolio-review",
    )

/** Queries about nothing in the corpus; whatever they score is the noise a floor has to sit above. */
internal val NONSENSE_PROBES =
    listOf(
        "zzzqqq nonsense token",
        "photosynthesis in tropical rainforest canopies",
        "the offside rule in association football",
    )

/** Proper nouns and a reference code: the lexical arm's territory, reported for comparison. */
internal val LEXICAL_PROBES = listOf("AldgateWealth", "raghunathan", "PLC-88213")

/** Partial-term queries a sparse arm should own, each to the title fragment of the document it should find. */
internal val SPARSE_DIAGNOSTIC_PROBES =
    listOf(
        "double taxation treaty" to "CRS Tax Residency",
        "electricity supplier statement" to "Electricity Account",
        "meter readings kWh unit rate" to "Electricity Account",
        "tax agreement between two countries" to "CRS Tax Residency",
    )
