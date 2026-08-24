package com.advisorsearch.search

import com.advisorsearch.clients.Client

data class ClientMatch(
    val client: Client,
    val score: Double,
    val matchedOn: String,
)
