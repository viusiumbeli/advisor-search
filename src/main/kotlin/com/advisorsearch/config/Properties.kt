package com.advisorsearch.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "embedding")
data class EmbeddingProperties(
    val modelPath: String,
    val tokenizerPath: String,
    val modelId: String,
    val maxTokens: Int,
    val chunkTokens: Int,
    val chunkOverlapTokens: Int,
)

@ConfigurationProperties(prefix = "search")
data class SearchProperties(
    val defaultLimit: Int,
    val maxLimit: Int,
    val semanticFloor: Double,
    val semanticFloorRatio: Double,
    val keywordFloorRatio: Double,
    val wordSimilarityThreshold: Double,
    val minFuzzyQueryLength: Int,
    val candidateDocuments: Int,
    val candidateChunks: Int,
    val rrfK: Int,
)

@ConfigurationProperties(prefix = "ingest")
data class IngestProperties(
    val maxContentLength: Int,
)

@ConfigurationProperties(prefix = "api")
data class ApiProperties(
    val key: String,
)
