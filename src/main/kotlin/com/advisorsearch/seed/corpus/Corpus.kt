package com.advisorsearch.seed.corpus

/** The parsed `seed/corpus.json` manifest: every demo client, each with its documents. */
data class Corpus(
    val clients: List<SeedClient>,
)

/** One demo client and the documents that belong to them. */
data class SeedClient(
    val firstName: String,
    val lastName: String,
    val email: String,
    val description: String,
    val socialLinks: List<String> = emptyList(),
    val documents: List<SeedDocument> = emptyList(),
)

/** One demo document: its title and the classpath file holding its text. */
data class SeedDocument(
    val title: String,
    val file: String,
)
