package com.advisorsearch.search.ranking

import java.util.UUID

/** [sources] is what lets a result explain itself: which retrievers found it. */
data class FusedDocument(
    val id: UUID,
    val score: Double,
    val sources: Set<String>,
)
