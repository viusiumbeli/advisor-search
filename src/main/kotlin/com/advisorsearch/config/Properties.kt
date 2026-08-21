package com.advisorsearch.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/*
 * All configuration is validated at startup — the same fail-fast principle the embedding config
 * applies to the model file. A zero max-limit or a negative floor would not crash on boot; it
 * would quietly return wrong search results, which is worse.
 */

@Validated
@ConfigurationProperties(prefix = "embedding")
data class EmbeddingProperties(
    @field:NotBlank val modelPath: String,
    @field:NotBlank val tokenizerPath: String,
    @field:NotBlank val modelId: String,
    @field:Positive val maxTokens: Int,
    @field:Positive val chunkTokens: Int,
    @field:PositiveOrZero val chunkOverlapTokens: Int,
)

@Validated
@ConfigurationProperties(prefix = "search")
data class SearchProperties(
    @field:Positive val defaultLimit: Int,
    @field:Positive val maxLimit: Int,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val semanticFloor: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val semanticFloorRatio: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val keywordFloorRatio: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val wordSimilarityThreshold: Double,
    @field:Positive val minFuzzyQueryLength: Int,
    @field:Positive val candidateDocuments: Int,
    @field:Positive val candidateChunks: Int,
    @field:Positive val rrfK: Int,
)

@Validated
@ConfigurationProperties(prefix = "ingest")
data class IngestProperties(
    @field:Positive val maxContentLength: Int,
)

@ConfigurationProperties(prefix = "api")
data class ApiProperties(
    /** Blank means authentication is off, which is the local default — so no constraint here. */
    val key: String,
)
