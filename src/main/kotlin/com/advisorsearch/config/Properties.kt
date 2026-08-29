package com.advisorsearch.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/*
 * Validated at startup: a zero max-limit or a negative floor would not crash on boot, it would
 * quietly return wrong search results, which is worse.
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

/** pgvector's storage cap on the non-zero elements of one sparsevec value. */
private const val SPARSEVEC_MAX_TERMS = 16_000L

@Validated
@ConfigurationProperties(prefix = "sparse")
data class SparseProperties(
    @field:NotBlank val modelPath: String,
    @field:NotBlank val tokenizerPath: String,
    @field:NotBlank val idfPath: String,
    @field:NotBlank val modelId: String,
    /** Bounded by what pgvector can store at all; the default sits at its stricter HNSW ceiling, see application.yml. */
    @field:Positive @field:Max(SPARSEVEC_MAX_TERMS) val maxTerms: Int,
)

@Validated
@ConfigurationProperties(prefix = "search")
data class SearchProperties(
    @field:Positive val defaultLimit: Int,
    @field:Positive val maxLimit: Int,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val semanticFloor: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val semanticFloorRatio: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val keywordFloorRatio: Double,
    /** An inner product over the query's own mass, not a cosine, so there is no upper bound to validate against. */
    @field:DecimalMin("0.0") val sparseFloor: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val sparseFloorRatio: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val wordSimilarityThreshold: Double,
    @field:Positive val minFuzzyQueryLength: Int,
    @field:Positive val candidateDocuments: Int,
    @field:Positive val rrfK: Int,
)

/** The `documents_content_length` CHECK in V1__initial_schema.sql. */
private const val CONTENT_LENGTH_CEILING = 100_000L

@Validated
@ConfigurationProperties(prefix = "ingest")
data class IngestProperties(
    /**
     * Bounded above by the schema's own ceiling, which the migration says this must never exceed.
     * Raising it past that would let a document pay for chunking and inference and then die on a
     * CHECK constraint — an opaque 500 in place of the 400 that names the limit. A misconfiguration
     * this cheap to state should fail the instance, not the request.
     */
    @field:Positive @field:Max(CONTENT_LENGTH_CEILING) val maxContentLength: Int,
)

@ConfigurationProperties(prefix = "api")
data class ApiProperties(
    /** Blank means authentication is off, which is the local default — so no constraint here. */
    val key: String,
)
