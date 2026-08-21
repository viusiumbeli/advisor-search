package com.advisorsearch.seed

/** What one seeding pass did — the counts that make the idempotency visible in the logs. */
data class SeedSummary(
    val clientsCreated: Int,
    val documentsCreated: Int,
    val skipped: Int,
)
