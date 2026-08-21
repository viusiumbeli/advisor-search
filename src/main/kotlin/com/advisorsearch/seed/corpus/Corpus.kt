package com.advisorsearch.seed.corpus

/** The parsed `seed/corpus.json` manifest: every demo client, each with its documents. */
data class Corpus(
    val clients: List<SeedClient>,
)
