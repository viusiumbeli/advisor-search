package com.advisorsearch.seed.corpus

/** The parsed `seed/corpus.json` manifest: every demo client, each with its documents. */
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
