package com.advisorsearch.search

import com.advisorsearch.clients.Client

/** A client hit before it becomes a response: the client, its score and which field matched. */
data class ClientMatch(
    val client: Client,
    val score: Double,
    val matchedOn: String,
)
