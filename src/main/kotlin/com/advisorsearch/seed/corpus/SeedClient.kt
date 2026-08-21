package com.advisorsearch.seed.corpus

/** One demo client and the documents that belong to them. */
data class SeedClient(
    val firstName: String,
    val lastName: String,
    val email: String,
    val description: String,
    val socialLinks: List<String> = emptyList(),
    val documents: List<SeedDocument> = emptyList(),
)
