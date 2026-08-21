package com.advisorsearch.search.ranking

import java.util.UUID

/**
 * A document after fusion: its combined reciprocal-rank weight and which retrievers found it.
 * [sources] is what lets a result explain itself as keyword, semantic or both.
 */
data class FusedDocument(
    val id: UUID,
    val score: Double,
    val sources: Set<String>,
)
